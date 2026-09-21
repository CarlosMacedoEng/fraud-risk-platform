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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * Serves the compiled strategy active in this service's environment, per tenant, including percentage
 * (canary) rollout of a candidate version. Strategies are compiled once and cached; a cheap periodic
 * check of the deployment row picks up promotions/rollbacks made through any instance, and
 * {@code ConfigurationChanged} events trigger an immediate refresh.
 *
 * <p>Canary bucketing is by customer (CRC32 of tenant:customer mod 100), so a customer sees one strategy
 * consistently and stays in the canary as the percentage is increased.
 */
@Component
public class ActiveStrategyProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveStrategyProvider.class);

    record Cached(CompiledStrategy active, CompiledStrategy candidate, int rolloutPercentage, int rowVersion) {
    }

    private final StrategyRepository repository;
    private final StrategyCompiler compiler;
    private final ObjectMapper json;
    private final String environment;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, CompiledStrategy> compiledByVersion = new ConcurrentHashMap<>();

    public ActiveStrategyProvider(StrategyRepository repository, StrategyCompiler compiler, ObjectMapper json,
                                  PlatformProperties props) {
        this.repository = repository;
        this.compiler = compiler;
        this.json = json;
        this.environment = props.environment();
    }

    /** Active strategy (ignores canary); used by health checks and admin views. */
    public CompiledStrategy get(String tenant) {
        return cached(tenant).active();
    }

    /** Strategy that applies to this customer, taking an in-progress canary into account. */
    public CompiledStrategy forCustomer(String tenant, String customerId) {
        Cached c = cached(tenant);
        if (c.candidate() != null && bucket(tenant, customerId) < c.rolloutPercentage()) {
            return c.candidate();
        }
        return c.active();
    }

    public static int bucket(String tenant, String customerId) {
        CRC32 crc = new CRC32();
        crc.update((tenant + ":" + customerId).getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % 100);
    }

    /** Compile any stored version (cached by checksum-stable version key). */
    public CompiledStrategy compiled(String tenant, String version) {
        return compiledByVersion.computeIfAbsent(tenant + "/" + version, k -> {
            var stored = repository.findVersion(tenant, version).orElseThrow(() ->
                    new PlatformException(ErrorCode.NOT_FOUND, "strategy version " + version + " not found"));
            return compiler.compile(tenant, json.readTree(stored.definition()));
        });
    }

    private Cached cached(String tenant) {
        Cached c = cache.get(tenant);
        return c != null ? c : load(tenant);
    }

    private synchronized Cached load(String tenant) {
        var d = repository.findDeployment(tenant, environment)
                .orElseThrow(() -> new PlatformException(ErrorCode.UNKNOWN_TENANT,
                        "no strategy deployed for tenant " + tenant + " in " + environment));
        CompiledStrategy active = compiled(tenant, d.activeVersion());
        CompiledStrategy candidate = d.candidateVersion() == null ? null : compiled(tenant, d.candidateVersion());
        Cached c = new Cached(active, candidate, d.rolloutPercentage(), d.rowVersion());
        Cached previous = cache.put(tenant, c);
        if (previous == null || previous.rowVersion() != c.rowVersion()) {
            log.info("strategy deployment loaded tenant={} env={} active={} candidate={} rollout={}% model={}",
                    tenant, environment, active.version(), candidate == null ? "-" : candidate.version(),
                    d.rolloutPercentage(), active.modelVersion());
        }
        return c;
    }

    /** Reload if the deployment changed since we cached it. */
    public void refresh(String tenant) {
        Cached c = cache.get(tenant);
        repository.findDeployment(tenant, environment).ifPresent(d -> {
            if (c == null || c.rowVersion() != d.rowVersion()) load(tenant);
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
