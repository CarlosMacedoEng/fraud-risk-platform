package com.fraudplatform.decision.api;

import com.fraudplatform.commons.error.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Admission control (backpressure) for the scoring endpoint.
 *
 * <p>Without it, virtual threads accept unlimited concurrent requests; beyond capacity they all queue on the
 * database pool, time out and fail — throughput collapses <em>below</em> capacity (measured in the stress
 * test, journal J-20). With a bounded number of in-flight requests, excess load is rejected in milliseconds
 * with {@code 503 OVERLOADED} + {@code Retry-After}, the gateway applies its stand-in policy, and the
 * requests that are admitted keep their latency SLO.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AdmissionControlFilter extends OncePerRequestFilter {

    private final Semaphore permits;
    private final long queueTimeoutMs;
    private final int maxConcurrent;
    private final ObjectMapper json;
    private final Counter rejected;

    public AdmissionControlFilter(@Value("${platform.admission.max-concurrent:32}") int maxConcurrent,
                                  @Value("${platform.admission.queue-timeout-ms:20}") long queueTimeoutMs,
                                  ObjectMapper json, MeterRegistry meters) {
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true);
        this.queueTimeoutMs = queueTimeoutMs;
        this.json = json;
        this.rejected = meters.counter("risk.admission.rejected");
        Gauge.builder("risk.admission.inflight", permits, p -> maxConcurrent - p.availablePermits())
                .description("Scoring requests currently admitted").register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/v1/decisions".equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean admitted;
        try {
            admitted = permits.tryAcquire(queueTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            admitted = false;
        }
        if (!admitted) {
            rejected.increment();
            response.setStatus(ErrorCode.OVERLOADED.httpStatus());
            response.setHeader("Retry-After", "1");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write(json.writeValueAsString(ApiExceptionHandler.problem(ErrorCode.OVERLOADED,
                    "scoring capacity exceeded (" + maxConcurrent + " in flight); apply stand-in policy and retry", null)));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }
}
