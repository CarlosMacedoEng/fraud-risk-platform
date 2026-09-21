package com.fraudplatform.decision.observability;

import com.fraudplatform.decision.application.ActiveStrategyProvider;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.inference.ModelRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness contributors. "models" and "strategies" are part of the readiness group: an instance whose
 * active strategy cannot be compiled, or whose active model failed to load, should not receive traffic.
 *
 * <p>Models are loaded once at startup, so a load failure is a deployment defect (wrong path, missing artifact),
 * not a transient outage: such an instance is strictly worse than the peers it would replace, and letting it
 * become ready turns a bad rollout into 100% rules-only fallback (TS-16, journal J-29). Runtime inference
 * failures are still handled per request by the fallback policy. {@code platform.readiness.require-models=false}
 * is the explicit opt-out for a deliberate rules-only operation.
 */
@Configuration
public class PlatformHealthIndicators {

    @Bean
    HealthIndicator strategies(ActiveStrategyProvider provider, PlatformProperties props) {
        return () -> {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean ok = true;
            for (String tenant : props.tenants().keySet()) {
                try {
                    var s = provider.get(tenant);
                    details.put(tenant, Map.of("version", s.version(), "model", s.modelVersion(), "checksum", s.checksum().substring(0, 12)));
                } catch (RuntimeException e) {
                    ok = false;
                    details.put(tenant, "ERROR: " + e.getMessage());
                }
            }
            return (ok ? Health.up() : Health.down()).withDetails(details).build();
        };
    }

    @Bean
    HealthIndicator models(ModelRegistry registry, ActiveStrategyProvider provider, PlatformProperties props,
                           @Value("${platform.readiness.require-models:true}") boolean requireModels) {
        return () -> {
            Map<String, Object> details = new LinkedHashMap<>();
            boolean allLoaded = true;
            for (String tenant : props.tenants().keySet()) {
                try {
                    String version = provider.get(tenant).modelVersion();
                    boolean loaded = registry.get(tenant, version).isPresent();
                    allLoaded &= loaded;
                    details.put(tenant, Map.of("version", version, "loaded", loaded));
                } catch (RuntimeException e) {
                    allLoaded = false;
                    details.put(tenant, "ERROR: " + e.getMessage());
                }
            }
            if (!registry.failures().isEmpty()) details.put("failures", registry.failures());
            Health.Builder health = allLoaded || !requireModels ? Health.up() : Health.down();
            return health.withDetail("allModelsLoaded", allLoaded).withDetail("requireModels", requireModels)
                    .withDetails(details).build();
        };
    }
}
