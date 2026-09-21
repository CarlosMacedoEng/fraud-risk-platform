package com.fraudplatform.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Map;

/**
 * Outbound integrations ({@code platform.integrations.<name>}). Timeouts are chosen from the latency
 * budget of the caller: synchronous hot-path lookups get tens of milliseconds and no retries; asynchronous
 * calls (case creation, explanations) get seconds and retries.
 */
@ConfigurationProperties(prefix = "platform.integrations")
public record IntegrationsProperties(Map<String, Endpoint> endpoints) {

    public record Endpoint(boolean enabled, URI baseUrl, long connectTimeoutMs, long attemptTimeoutMs, long deadlineMs,
                           int maxAttempts, long initialBackoffMs, int maxConcurrentCalls) {
    }

    public Endpoint get(String name) {
        return endpoints == null ? null : endpoints.get(name);
    }
}
