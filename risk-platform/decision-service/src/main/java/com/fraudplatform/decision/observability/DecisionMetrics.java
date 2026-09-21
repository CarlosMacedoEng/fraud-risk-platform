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

    public DecisionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordDecision(RiskDecision d, double totalMs) {
        boolean degraded = !d.degradedModes().isEmpty();
        Timer.builder("risk.decision.latency")
                .description("Server-side scoring latency including persistence")
                .tags("tenant", d.tenantId(), "decision", d.decision().name(), "degraded", Boolean.toString(degraded))
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100), Duration.ofMillis(250))
                .register(registry)
                .record((long) (totalMs * 1_000_000), TimeUnit.NANOSECONDS);
        registry.counter("risk.decisions", "tenant", d.tenantId(), "decision", d.decision().name()).increment();
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
