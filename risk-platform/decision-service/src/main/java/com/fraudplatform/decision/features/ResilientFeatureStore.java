package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Primary (Redis) feature store guarded by a circuit breaker, with a PostgreSQL fallback for reads.
 *
 * <ul>
 *   <li>Redis healthy: read + write Redis.</li>
 *   <li>Redis failing: circuit opens after the configured failure rate; reads go to PostgreSQL (slower,
 *       approximate "seen" sets), decisions are flagged {@code FEATURE_STORE_DEGRADED}; writes are skipped
 *       and counted — velocity state for the outage window is rebuilt later from the transactions table.</li>
 * </ul>
 */
public class ResilientFeatureStore {

    private static final Logger log = LoggerFactory.getLogger(ResilientFeatureStore.class);

    public record Loaded(EntityState state, boolean degraded) {
    }

    private final FeatureStore primary;
    private final FeatureStore fallback;
    private final CircuitBreaker breaker;
    private final Counter fallbackReads;
    private final Counter skippedWrites;

    public ResilientFeatureStore(FeatureStore primary, FeatureStore fallback, CircuitBreaker breaker, MeterRegistry meters) {
        this.primary = primary;
        this.fallback = fallback;
        this.breaker = breaker;
        this.fallbackReads = meters.counter("risk.featurestore.fallback.reads");
        this.skippedWrites = meters.counter("risk.featurestore.skipped.writes");
    }

    public Loaded load(Transaction tx) {
        try {
            return new Loaded(breaker.executeSupplier(() -> primary.load(tx)), false);
        } catch (CallNotPermittedException open) {
            return fallbackLoad(tx, "circuit open");
        } catch (RuntimeException e) {
            return fallbackLoad(tx, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Loaded fallbackLoad(Transaction tx, String cause) {
        fallbackReads.increment();
        log.warn("feature store degraded, using PostgreSQL fallback ({})", cause);
        try {
            return new Loaded(fallback.load(tx), true);
        } catch (RuntimeException e) {
            log.error("feature store fallback failed; scoring with empty state", e);
            return new Loaded(EntityState.empty(), true);
        }
    }

    public void record(Transaction tx) {
        try {
            breaker.executeRunnable(() -> primary.record(tx));
        } catch (RuntimeException e) {
            skippedWrites.increment();
            log.warn("feature store write skipped for {}: {}", tx.transactionId(), e.toString());
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }
}
