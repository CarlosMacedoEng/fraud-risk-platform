package com.fraudplatform.decision.integration;

import com.fraudplatform.commons.integration.IntegrationClient;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.Map;

/**
 * Python model-service ({@code POST /v1/explanations}) — SHAP explanations for investigators.
 * Off the scoring path by design (ADR-001): called only when someone opens a decision.
 */
public class ModelServiceClient {

    static final IntegrationClient.Operation EXPLAIN = new IntegrationClient.Operation("explain", true);

    private final IntegrationClient client;
    private final Duration deadline;

    public ModelServiceClient(IntegrationClient client, Duration deadline) {
        this.client = client;
        this.deadline = deadline;
    }

    public JsonNode explain(String tenant, String modelVersion, Map<String, Double> features) {
        return client.post(EXPLAIN, "/v1/explanations",
                Map.of("customerId", tenant, "modelVersion", modelVersion, "features", features), null, JsonNode.class,
                n -> n.has("contributions") && n.has("probability"), deadline);
    }
}
