package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.domain.TransactionType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Journal J-18: the PostgreSQL fallback must be bounded so a Redis problem cannot exhaust the DB pool. */
class ResilientFeatureStoreTest {

    static final Transaction TX = new Transaction("t", "tx1", "c1", "a1", Instant.now(), TransactionType.CARD_PAYMENT,
            Channel.ECOM, BigDecimal.TEN, "EUR", "tok", "m", "5411", "PT", null, null, "d", "1.1.1.1", "PT");

    @Test
    void fallbackIsBoundedAndExcessLoadGetsEmptyDegradedState() throws Exception {
        FeatureStore broken = new FeatureStore() {
            public EntityState load(Transaction tx) { throw new IllegalStateException("redis down"); }
            public void record(Transaction tx) { throw new IllegalStateException("redis down"); }
        };
        CountDownLatch inFallback = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FeatureStore slowDb = new FeatureStore() {
            public EntityState load(Transaction tx) {
                inFallback.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
                return EntityState.empty();
            }
            public void record(Transaction tx) { }
        };
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ResilientFeatureStore store = new ResilientFeatureStore(broken, slowDb, CircuitBreaker.ofDefaults("t"), meters, 1);

        Thread holder = Thread.ofVirtual().start(() -> store.load(TX));
        assertThat(inFallback.await(2, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        ResilientFeatureStore.Loaded second = store.load(TX);   // permit taken: must not wait on the database
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(200);
        assertThat(second.degraded()).isTrue();
        assertThat(meters.counter("risk.featurestore.fallback.rejected").count()).isEqualTo(1.0);

        release.countDown();
        holder.join();
        store.record(TX);   // write failure is swallowed and counted
        assertThat(meters.counter("risk.featurestore.skipped.writes").count()).isEqualTo(1.0);
    }
}
