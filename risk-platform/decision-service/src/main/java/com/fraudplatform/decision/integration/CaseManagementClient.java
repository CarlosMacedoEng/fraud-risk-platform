package com.fraudplatform.decision.integration;

import com.fraudplatform.commons.integration.IntegrationClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Case management API ({@code POST /cases/v1/cases}). Creating a case is not naturally idempotent, but it
 * is sent with {@code Idempotency-Key = decisionId}, which makes retries safe — so the operation is declared
 * idempotent and the client may retry transient failures within the deadline.
 */
public class CaseManagementClient {

    static final IntegrationClient.Operation CREATE_CASE = new IntegrationClient.Operation("create-case", true);

    public record CaseResponse(String caseReference, String status) {
        boolean valid() {
            return caseReference != null && !caseReference.isBlank() && status != null;
        }
    }

    private final IntegrationClient client;
    private final Duration deadline;

    public CaseManagementClient(IntegrationClient client, Duration deadline) {
        this.client = client;
        this.deadline = deadline;
    }

    public CaseResponse create(String tenant, String decisionId, String transactionId, String customerId, String priority,
                               List<String> reasons) {
        Map<String, Object> body = Map.of("tenantId", tenant, "decisionId", decisionId, "transactionId", transactionId,
                "customerId", customerId, "priority", priority, "reasons", reasons);
        return client.post(CREATE_CASE, "/cases/v1/cases", body, decisionId, CaseResponse.class, CaseResponse::valid, deadline);
    }
}
