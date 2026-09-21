package com.fraudplatform.decision.application;

import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import com.fraudplatform.decision.config.PlatformProperties;
import com.fraudplatform.decision.persistence.StrategyRepository;
import com.fraudplatform.decision.strategy.CompiledStrategy;
import com.fraudplatform.decision.strategy.StrategyCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the compiled strategy active in this service's environment, per tenant. Strategies are
 * compiled once and cached; a cheap periodic check of the deployment row picks up promotions and
 * rollbacks made by any instance (and {@code ConfigurationChanged} events trigger an immediate refresh).
 */
@Component
public class ActiveStrategyProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveStrategyProvider.class);

    private record Cached(CompiledStrategy strategy, int deploymentRowVersion) {
    }

    private final StrategyRepository repository;
    private final StrategyCompiler compiler;
    private final ObjectMapper json;
    private final String environment;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public ActiveStrategyProvider(StrategyRepository repository, StrategyCompiler compiler, ObjectMapper json,
                                  PlatformProperties props) {
        this.repository = repository;
        this.compiler = compiler;
        this.json = json;
        this.environment = props.environment();
    }

    public CompiledStrategy get(String tenant) {
        Cached c = cache.get(tenant);
        if (c != null) return c.strategy();
        return load(tenant).strategy();
    }

    private synchronized Cached load(String tenant) {
        var deployment = repository.findDeployment(tenant, environment)
                .orElseThrow(() -> new PlatformException(ErrorCode.UNKNOWN_TENANT,
                        "no strategy deployed for tenant " + tenant + " in " + environment));
        var stored = repository.findVersion(tenant, deployment.activeVersion()).orElseThrow();
        CompiledStrategy compiled = compiler.compile(tenant, json.readTree(stored.definition()));
        Cached c = new Cached(compiled, deployment.rowVersion());
        Cached previous = cache.put(tenant, c);
        if (previous == null || !previous.strategy().version().equals(compiled.version())) {
            log.info("strategy activated tenant={} env={} version={} model={} checksum={}", tenant, environment,
                    compiled.version(), compiled.modelVersion(), compiled.checksum().substring(0, 12));
        }
        return c;
    }

    /** Reload if the deployment changed since we cached it. */
    public void refresh(String tenant) {
        Cached c = cache.get(tenant);
        repository.findDeployment(tenant, environment).ifPresent(d -> {
            if (c == null || c.deploymentRowVersion() != d.rowVersion()) load(tenant);
        });
    }

    @Scheduled(fixedDelayString = "${platform.strategy-refresh-ms:15000}")
    public void refreshAll() {
        for (String tenant : cache.keySet()) {
            try {
                refresh(tenant);
            } catch (RuntimeException e) {
                log.error("strategy refresh failed tenant={}; keeping previous version", tenant, e);
            }
        }
    }

    public String environment() {
        return environment;
    }
}
