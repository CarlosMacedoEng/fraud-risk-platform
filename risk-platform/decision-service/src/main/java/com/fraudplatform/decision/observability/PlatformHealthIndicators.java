package com.fraudplatform.decision.observability;

import com.fraudplatform.decision.application.ActiveStrategyProvider;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.inference.ModelRegistry;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Readiness contributors. "models" and "strategies" are part of the readiness group: an instance whose
 * active strategy cannot be compiled should not receive traffic. A model that fails to load does NOT
 * make the instance unready — the service can still decide in fallback mode — but health shows it.
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
    HealthIndicator models(ModelRegistry registry, ActiveStrategyProvider provider, PlatformProperties props) {
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
                    details.put(tenant, "ERROR: " + e.getMessage());
                }
            }
            if (!registry.failures().isEmpty()) details.put("failures", registry.failures());
            // Degraded but still serving (rules-only fallback): report UP with details, alert on the metric.
            return Health.up().withDetail("allModelsLoaded", allLoaded).withDetails(details).build();
        };
    }
}
