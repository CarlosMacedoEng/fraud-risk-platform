package com.fraudplatform.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Service configuration. Environment-specific values come from profiles / environment variables;
 * customer-specific risk configuration lives in the database (strategy versions), not here.
 */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(
        String environment,
        Path modelsDir,
        Path configDir,
        Budgets budgets,
        Bootstrap bootstrap,
        Map<String, Tenant> tenants,
        List<ApiClient> clients,
        Faults faults) {

    /** Per-dependency time budgets in milliseconds (see ARCHITECTURE.md §4.1). */
    public record Budgets(long profileMs, long featureStoreMs, long graphMs, long modelMs, long deviceRiskMs,
                          int inferenceThreads) {
    }

    public record Bootstrap(boolean importStrategies, boolean loadGraphSnapshots) {
    }

    public record Tenant(String defaultHomeCountry, String currency) {
    }

    /** API client. Only the SHA-256 of the key is configured, never the key itself. */
    public record ApiClient(String clientId, String tenantId, String keySha256, List<String> roles) {
    }

    public record Faults(boolean enabled) {
    }

    public Tenant tenant(String tenantId) {
        Tenant t = tenants == null ? null : tenants.get(tenantId);
        return t != null ? t : new Tenant("PT", "EUR");
    }
}
