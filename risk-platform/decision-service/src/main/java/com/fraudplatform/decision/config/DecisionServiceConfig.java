package com.fraudplatform.decision.config;

import com.fraudplatform.decision.application.StrategyBootstrap;
import com.fraudplatform.decision.features.FeatureStore;
import com.fraudplatform.decision.features.JdbcFallbackFeatureStore;
import com.fraudplatform.decision.features.RedisFeatureStore;
import com.fraudplatform.decision.features.RedisGraphFeatureStore;
import com.fraudplatform.decision.features.ResilientFeatureStore;
import com.fraudplatform.decision.inference.ModelRegistry;
import com.fraudplatform.decision.inference.ModelScorer;
import com.fraudplatform.decision.lab.FaultInjector;
import com.fraudplatform.decision.persistence.StrategyRepository;
import com.fraudplatform.decision.strategy.StrategyCompiler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Configuration
public class DecisionServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(DecisionServiceConfig.class);

    @Bean
    StrategyCompiler strategyCompiler() {
        return new StrategyCompiler();
    }

    @Bean
    FaultInjector faultInjector(PlatformProperties props) {
        return new FaultInjector(props.faults() != null && props.faults().enabled());
    }

    // ------------------------------------------------------------------ executors

    /** Enrichment calls are I/O-bound: one virtual thread per call. */
    @Bean(destroyMethod = "close")
    ExecutorService enrichmentExecutor() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("enrich-", 0).factory());
    }

    /**
     * Inference is CPU-bound: a small fixed pool with a bounded queue (bulkhead). When saturated,
     * submissions are rejected and the request falls back to rules-only rather than queueing.
     */
    @Bean(destroyMethod = "shutdown")
    ExecutorService inferenceExecutor(PlatformProperties props, MeterRegistry meters) {
        int threads = props.budgets().inferenceThreads();
        AtomicInteger n = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * 50), r -> {
            Thread t = new Thread(r, "inference-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        return ExecutorServiceMetrics.monitor(meters, pool, "inference");
    }

    // ------------------------------------------------------------------ resilience

    @Bean
    CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meters) {
        CircuitBreakerConfig redis = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofMillis(50))
                .slowCallRateThreshold(80)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(redis);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meters);
        return registry;
    }

    // ------------------------------------------------------------------ features

    @Bean
    ResilientFeatureStore featureStore(StringRedisTemplate redis, DataSource dataSource, CircuitBreakerRegistry breakers,
                                       MeterRegistry meters, FaultInjector faults) {
        JdbcTemplate fallbackJdbc = new JdbcTemplate(dataSource);
        fallbackJdbc.setQueryTimeout(1);
        FeatureStore redisStore = new RedisFeatureStore(redis);
        FeatureStore primary = new FeatureStore() {
            @Override
            public com.fraudplatform.decision.features.EntityState load(com.fraudplatform.decision.domain.Transaction tx) {
                faults.apply(FaultInjector.Point.FEATURE_STORE);
                return redisStore.load(tx);
            }

            @Override
            public void record(com.fraudplatform.decision.domain.Transaction tx) {
                faults.apply(FaultInjector.Point.FEATURE_STORE);
                redisStore.record(tx);
            }
        };
        return new ResilientFeatureStore(primary, new JdbcFallbackFeatureStore(JdbcClient.create(fallbackJdbc), 1),
                breakers.circuitBreaker("redis-features"), meters);
    }

    @Bean
    RedisGraphFeatureStore graphFeatureStore(StringRedisTemplate redis, CircuitBreakerRegistry breakers, ObjectMapper json) {
        return new RedisGraphFeatureStore(redis, breakers.circuitBreaker("redis-graph"), json);
    }

    // ------------------------------------------------------------------ models

    @Bean(destroyMethod = "close")
    ModelRegistry modelRegistry(PlatformProperties props, ObjectMapper json) {
        return new ModelRegistry(props.modelsDir().toAbsolutePath().normalize(), json);
    }

    @Bean
    ModelScorer modelScorer(ModelRegistry registry, ExecutorService inferenceExecutor, PlatformProperties props,
                            FaultInjector faults, MeterRegistry meters) {
        return new ModelScorer(registry, inferenceExecutor, props.budgets().modelMs(), faults, meters);
    }

    // Domain events: see messaging/OutboxWriter (transactional outbox).

    // ------------------------------------------------------------------ startup

    @Bean
    @Order(1)
    ApplicationRunner strategyBootstrap(StrategyRepository repo, StrategyCompiler compiler, ObjectMapper json, PlatformProperties props,
                                        com.fraudplatform.decision.application.ModelGovernanceService models) {
        return args -> {
            if (props.bootstrap() != null && props.bootstrap().importStrategies()) {
                new StrategyBootstrap(repo, compiler, json, props).run();
                // Register the models referenced by imported strategies (primary -> champion, challenger -> shadow).
                for (String tenant : props.tenants().keySet()) {
                    repo.findDeployment(tenant, props.environment()).ifPresent(d -> {
                        var active = repo.findVersion(tenant, d.activeVersion()).orElseThrow();
                        JsonNode model = json.readTree(active.definition()).path("model");
                        JsonNode ch = model.path("challengerVersion");
                        models.bootstrap(tenant, model.path("version").asString(),
                                ch.isMissingNode() || ch.isNull() ? null : ch.asString());
                    });
                }
            }
        };
    }

    @Bean
    @Order(2)
    ApplicationRunner graphSnapshotLoader(RedisGraphFeatureStore graph, PlatformProperties props) {
        return args -> {
            if (props.bootstrap() == null || !props.bootstrap().loadGraphSnapshots()) return;
            Path models = props.modelsDir();
            if (!Files.isDirectory(models)) return;
            try (Stream<Path> tenants = Files.list(models)) {
                for (Path t : tenants.filter(Files::isDirectory).toList()) {
                    Path snapshot = t.resolve("graph").resolve("graph-features-latest.jsonl");
                    if (Files.exists(snapshot)) {
                        try {
                            int n = graph.loadSnapshot(t.getFileName().toString(), snapshot);
                            log.info("graph snapshot loaded tenant={} entities={}", t.getFileName(), n);
                        } catch (Exception e) {
                            log.warn("graph snapshot load failed tenant={}: {}", t.getFileName(), e.toString());
                        }
                    }
                }
            }
        };
    }
}
