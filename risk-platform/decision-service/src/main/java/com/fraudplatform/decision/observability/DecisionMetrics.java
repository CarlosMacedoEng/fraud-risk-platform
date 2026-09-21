package com.fraudplatform.decision.observability;

import com.fraudplatform.decision.domain.DegradedMode;
import com.fraudplatform.decision.domain.RiskDecision;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Business and technical metrics for the scoring path. Names follow {@code risk.<area>.<metric>}; tags are
 * low-cardinality only (tenant, decision, degraded, mode) — never transaction or customer IDs.
 */
@Component
public class DecisionMetrics {

    private final MeterRegistry registry;
    // Meters are cached per tag combination: building/registering them on every request showed up in the
    // JFR profile (tag sorting, registry lookups) under load.
    private final java.util.Map<String, Timer> timers = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, io.micrometer.core.instrument.Counter> counters = new java.util.concurrent.ConcurrentHashMap<>();

    public DecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDecision(RiskDecision d, double totalMs) {
        boolean degraded = !d.degradedModes().isEmpty();
        String key = d.tenantId() + "|" + d.decision() + "|" + degraded;
        timers.computeIfAbsent(key, k -> Timer.builder("risk.decision.latency")
                        .description("Server-side scoring latency including persistence")
                        .tags("tenant", d.tenantId(), "decision", d.decision().name(), "degraded", Boolean.toString(degraded))
                        .publishPercentileHistogram()
                        .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250))
                        .register(registry))
                .record((long) (totalMs * 1_000_000), TimeUnit.NANOSECONDS);
        counters.computeIfAbsent("d|" + d.tenantId() + "|" + d.decision(),
                k -> registry.counter("risk.decisions", "tenant", d.tenantId(), "decision", d.decision().name())).increment();
        for (DegradedMode m : d.degradedModes()) {
            registry.counter("risk.decisions.degraded", "tenant", d.tenantId(), "mode", m.name()).increment();
        }
        registry.summary("risk.decision.score", "tenant", d.tenantId()).record(d.riskScore());
    }

    public void idempotentReplay(String tenant) {
        registry.counter("risk.idempotency.replays", "tenant", tenant).increment();
    }

    public void dependencyTimeout(String dependency) {
        registry.counter("risk.dependency.timeouts", "dependency", dependency).increment();
    }
}
