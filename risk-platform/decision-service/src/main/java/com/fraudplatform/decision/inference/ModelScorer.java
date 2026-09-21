package com.fraudplatform.decision.inference;

import com.fraudplatform.decision.features.FeatureVector;
import com.fraudplatform.decision.lab.FaultInjector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs inference on a bounded executor with a per-call time budget.
 *
 * <p>The executor is a bulkhead: if inference becomes slow (CPU starvation, a pathological model)
 * requests fail fast into the rules-only fallback instead of piling up web-server threads.
 * An empty result means "model unavailable" and is handled by the caller's fallback policy.
 */
public class ModelScorer {

    private static final Logger log = LoggerFactory.getLogger(ModelScorer.class);

    private final ModelRegistry registry;
    private final ExecutorService executor;
    private final long budgetMs;
    private final FaultInjector faults;
    private final Timer timer;
    private final MeterRegistry meters;

    public ModelScorer(ModelRegistry registry, ExecutorService executor, long budgetMs, FaultInjector faults, MeterRegistry meters) {
        this.registry = registry;
        this.executor = executor;
        this.budgetMs = budgetMs;
        this.faults = faults;
        this.meters = meters;
        this.timer = Timer.builder("risk.model.inference").publishPercentileHistogram().register(meters);
    }

    public Optional<OnnxModelBundle.Scores> score(String tenant, String version, FeatureVector features) {
        Optional<OnnxModelBundle> bundle = registry.get(tenant, version);
        if (bundle.isEmpty()) {
            failure("not_loaded");
            return Optional.empty();
        }
        long start = System.nanoTime();
        try {
            CompletableFuture<OnnxModelBundle.Scores> f = CompletableFuture.supplyAsync(() -> {
                faults.apply(FaultInjector.Point.MODEL_INFERENCE);
                try {
                    return bundle.get().score(features);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, executor);
            return Optional.of(f.get(budgetMs, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            failure("timeout");
            log.warn("model inference exceeded {}ms budget tenant={} version={}", budgetMs, tenant, version);
            return Optional.empty();
        } catch (RejectedExecutionException e) {
            failure("rejected");
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure("interrupted");
            return Optional.empty();
        } catch (Exception e) {
            failure("error");
            log.warn("model inference failed tenant={} version={}: {}", tenant, version, e.toString());
            return Optional.empty();
        } finally {
            timer.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    /** Shadow/challenger scoring: best effort, never affects the decision. */
    public Optional<Double> challengerProbability(String tenant, String version, FeatureVector features) {
        return registry.get(tenant, version).flatMap(b -> {
            try {
                return Optional.of(b.probability(features));
            } catch (Exception e) {
                failure("challenger_error");
                return Optional.empty();
            }
        });
    }

    private void failure(String reason) {
        meters.counter("risk.model.failures", "reason", reason).increment();
    }
}
