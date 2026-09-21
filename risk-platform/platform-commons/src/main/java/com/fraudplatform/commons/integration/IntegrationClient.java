package com.fraudplatform.commons.integration;

import com.fraudplatform.commons.correlation.Correlation;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Reusable outbound HTTP integration template (see docs/REUSABLE_ENGINEERING_ASSETS.md).
 *
 * <ul>
 *   <li><b>Deadline, not just timeouts</b>: each call has an overall budget; per-attempt timeouts and retry
 *       back-off are cut to what remains, so retries can never blow the caller's latency budget.</li>
 *   <li><b>Retries only when safe</b>: only for {@link Operation#idempotent()} operations and retryable
 *       failure kinds; exponential back-off with "equal jitter" (half fixed, half random).</li>
 *   <li><b>Circuit breaker</b> (fail fast when the dependency is sick) and <b>bulkhead</b> (cap concurrency
 *       so one slow dependency cannot absorb all threads).</li>
 *   <li><b>Correlation</b>: {@code X-Correlation-Id} from the MDC; <b>idempotency keys</b> on writes.</li>
 *   <li><b>Contract validation</b>: JSON is mapped to a record and checked by a predicate.</li>
 *   <li><b>Metrics</b>: {@code integration.client.requests} timer tagged by integration, operation, outcome.</li>
 * </ul>
 */
public class IntegrationClient {

    private static final Logger log = LoggerFactory.getLogger(IntegrationClient.class);

    public record Settings(String name, URI baseUrl, Duration connectTimeout, Duration attemptTimeout,
                           int maxAttempts, Duration initialBackoff, int maxConcurrentCalls,
                           float failureRateThreshold, Duration openStateWait, Map<String, String> defaultHeaders) {

        public static Settings defaults(String name, URI baseUrl) {
            return new Settings(name, baseUrl, Duration.ofMillis(200), Duration.ofMillis(500), 3,
                    Duration.ofMillis(50), 50, 50f, Duration.ofSeconds(10), Map.of());
        }
    }

    /** An operation of the remote API. {@code idempotent} decides whether automatic retries are allowed. */
    public record Operation(String name, boolean idempotent) {
    }

    private final Settings settings;
    private final HttpClient http;
    private final ObjectMapper json;
    private final CircuitBreaker breaker;
    private final Bulkhead bulkhead;
    private final MeterRegistry meters;

    public IntegrationClient(Settings settings, ObjectMapper json, MeterRegistry meters) {
        this.settings = settings;
        this.json = json;
        this.meters = meters;
        this.http = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1).build();
        this.breaker = CircuitBreaker.of(settings.name(), CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20).minimumNumberOfCalls(10)
                .failureRateThreshold(settings.failureRateThreshold())
                .waitDurationInOpenState(settings.openStateWait())
                .permittedNumberOfCallsInHalfOpenState(3)
                // Business outcomes (404) and our own defects (4xx) must not open the breaker.
                .ignoreException(e -> e instanceof IntegrationException ie
                        && (ie.kind() == IntegrationException.Kind.NOT_FOUND || ie.kind() == IntegrationException.Kind.CLIENT_ERROR))
                .build());
        this.bulkhead = Bulkhead.of(settings.name(), BulkheadConfig.custom()
                .maxConcurrentCalls(settings.maxConcurrentCalls()).maxWaitDuration(Duration.ZERO).build());
        meters.gauge("integration.circuit.state", java.util.List.of(io.micrometer.core.instrument.Tag.of("integration", settings.name())),
                breaker, b -> switch (b.getState()) {
                    case CLOSED -> 0;
                    case HALF_OPEN -> 1;
                    case OPEN, FORCED_OPEN -> 2;
                    default -> 3;
                });
    }

    public <T> T get(Operation op, String path, Class<T> type, Predicate<T> contract, Duration deadline) {
        return execute(op, "GET", path, null, null, type, contract, deadline);
    }

    public <T> T post(Operation op, String path, Object body, String idempotencyKey, Class<T> type, Predicate<T> contract,
                      Duration deadline) {
        return execute(op, "POST", path, body, idempotencyKey, type, contract, deadline);
    }

    private <T> T execute(Operation op, String method, String path, Object body, String idempotencyKey, Class<T> type,
                          Predicate<T> contract, Duration deadline) {
        long deadlineNanos = System.nanoTime() + deadline.toNanos();
        int attempt = 0;
        IntegrationException last = null;
        while (true) {
            attempt++;
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                throw last != null ? last : new IntegrationException(IntegrationException.Kind.TIMEOUT, settings.name(), 0,
                        "deadline exceeded before attempt " + attempt, null);
            }
            Duration attemptTimeout = Duration.ofNanos(Math.min(remaining, settings.attemptTimeout().toNanos()));
            try {
                return timed(op, () -> bulkhead.executeSupplier(() -> breaker.executeSupplier(() ->
                        send(op, method, path, body, idempotencyKey, type, contract, attemptTimeout))));
            } catch (CallNotPermittedException e) {
                throw new IntegrationException(IntegrationException.Kind.CIRCUIT_OPEN, settings.name(), 0, "circuit open", e);
            } catch (BulkheadFullException e) {
                throw new IntegrationException(IntegrationException.Kind.BULKHEAD_FULL, settings.name(), 0, "too many concurrent calls", e);
            } catch (IntegrationException e) {
                last = e;
                boolean canRetry = op.idempotent() && e.kind().retryable() && attempt < settings.maxAttempts();
                if (!canRetry) throw e;
                long backoff = backoffNanos(attempt);
                if (System.nanoTime() + backoff >= deadlineNanos) throw e;   // no time left for another attempt
                log.warn("{} {} attempt {} failed ({}), retrying in {} ms", settings.name(), op.name(), attempt, e.kind(),
                        backoff / 1_000_000);
                sleep(backoff);
            }
        }
    }

    private long backoffNanos(int attempt) {
        long cap = settings.initialBackoff().toNanos() * (1L << (attempt - 1));
        return ThreadLocalRandom.current().nextLong(cap / 2, cap + 1);   // "equal jitter"
    }

    private <T> T send(Operation op, String method, String path, Object body, String idempotencyKey, Class<T> type,
                       Predicate<T> contract, Duration timeout) {
        HttpRequest.Builder req = HttpRequest.newBuilder(settings.baseUrl().resolve(path)).timeout(timeout)
                .header("Accept", "application/json")
                .header(Correlation.HEADER, correlationId());
        settings.defaultHeaders().forEach(req::header);
        if (idempotencyKey != null) req.header("Idempotency-Key", idempotencyKey);
        if (body != null) {
            req.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res;
        try {
            res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new IntegrationException(IntegrationException.Kind.TIMEOUT, settings.name(), 0, "timeout after " + timeout.toMillis() + " ms", e);
        } catch (ConnectException e) {
            throw new IntegrationException(IntegrationException.Kind.UNAVAILABLE, settings.name(), 0, "connection failed", e);
        } catch (IOException e) {
            throw new IntegrationException(IntegrationException.Kind.UNAVAILABLE, settings.name(), 0, e.getClass().getSimpleName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegrationException(IntegrationException.Kind.UNAVAILABLE, settings.name(), 0, "interrupted", e);
        }
        int status = res.statusCode();
        if (status == 404) throw new IntegrationException(IntegrationException.Kind.NOT_FOUND, settings.name(), status, "not found", null);
        if (status == 429 || (status >= 500 && status != 501)) {
            throw new IntegrationException(IntegrationException.Kind.SERVER_ERROR, settings.name(), status, truncate(res.body()), null);
        }
        if (status >= 400) {
            throw new IntegrationException(IntegrationException.Kind.CLIENT_ERROR, settings.name(), status, truncate(res.body()), null);
        }
        T value;
        try {
            value = json.readValue(res.body(), type);
        } catch (RuntimeException e) {
            throw new IntegrationException(IntegrationException.Kind.CONTRACT_VIOLATION, settings.name(), status, "unparseable response", e);
        }
        if (value == null || (contract != null && !contract.test(value))) {
            throw new IntegrationException(IntegrationException.Kind.CONTRACT_VIOLATION, settings.name(), status,
                    "response violates contract", null);
        }
        return value;
    }

    private <T> T timed(Operation op, java.util.function.Supplier<T> call) {
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return call.get();
        } catch (IntegrationException e) {
            outcome = e.kind().name().toLowerCase();
            throw e;
        } catch (CallNotPermittedException e) {
            outcome = "circuit_open";
            throw e;
        } catch (BulkheadFullException e) {
            outcome = "bulkhead_full";
            throw e;
        } finally {
            Timer.builder("integration.client.requests").tags("integration", settings.name(), "operation", op.name(), "outcome", outcome)
                    .publishPercentileHistogram().register(meters).record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private static String correlationId() {
        String id = MDC.get(Correlation.MDC_CORRELATION_ID);
        return id != null ? id : UUID.randomUUID().toString();
    }

    private static String truncate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static void sleep(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Opens a connection and exercises the client code path before real traffic arrives (startup warm-up).
     * The response status is irrelevant; failures are ignored and do not count towards the circuit breaker.
     */
    public void warmUp(String path) {
        try {
            http.send(HttpRequest.newBuilder(settings.baseUrl().resolve(path)).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.info("{} warm-up failed (ignored): {}", settings.name(), e.toString());
        } 
    }

    public CircuitBreaker circuitBreaker() {
        return breaker;
    }

    public String name() {
        return settings.name();
    }
}
