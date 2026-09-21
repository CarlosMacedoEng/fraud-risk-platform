package com.fraudplatform.decision.api;

import com.fraudplatform.decision.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionApiIntegrationTest extends IntegrationTestBase {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    private ResponseEntity<String> score(String apiKey, String idemKey, String body) {
        var req = http.post().uri("/v1/decisions").header("X-Api-Key", apiKey).header("X-Correlation-Id", "it-" + idemKey);
        if (idemKey != null) req = req.header("Idempotency-Key", idemKey);
        return req.body(body).retrieve().toEntity(String.class);
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static String id() {
        return "IT-" + UUID.randomUUID();
    }

    @Test
    void scoresATransactionWithFullContract() throws Exception {
        String tx = id();
        var res = score(GATEWAY_KEY, tx, cardPayment(tx, "ALD-C000001", 35.50, "D-KNOWN-1", "PT", now()));
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        JsonNode body = JSON.readTree(res.getBody());
        assertThat(body.get("decision").asString()).isIn("APPROVE", "REVIEW", "DECLINE");
        assertThat(body.get("riskScore").asDouble()).isBetween(0.0, 1.0);
        assertThat(body.get("fraudProbability").asDouble()).isBetween(0.0, 1.0);
        assertThat(body.get("versions").get("model").asString()).isEqualTo("aldermoor-bank-lgbm-1.0.0");
        assertThat(body.get("versions").get("strategy").asString()).isEqualTo("1.1.0");
        assertThat(body.get("versions").get("featureSpec").asString()).isEqualTo("fs-1.0");
        assertThat(body.get("processingTimeMs").asDouble()).isPositive();
        // Normal operation must not be degraded: fallbacks are designed to hide failures from callers,
        // so tests (and alerts) must check for them explicitly.
        assertThat(body.get("degradedModes").size()).as(body.toString()).isZero();
        assertThat(body.get("correlationId").asString()).isEqualTo("it-" + tx);
        assertThat(res.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("it-" + tx);
        assertThat(res.getHeaders().getFirst("Server-Timing")).contains("total;dur=");
    }

    @Test
    void riskyPatternScoresHigherThanNormalBehaviour() throws Exception {
        String normal = id();
        JsonNode a = JSON.readTree(score(GATEWAY_KEY, normal,
                cardPayment(normal, "ALD-C000001", 30.00, "D-KNOWN-1", "PT", now())).getBody());
        // New device, foreign merchant, 25x the customer's baseline, then a burst on the same card.
        String device = "D-NEW-" + UUID.randomUUID();
        JsonNode first = null, last = null;
        for (int i = 0; i < 4; i++) {
            String tx = id();
            last = JSON.readTree(score(GATEWAY_KEY, tx,
                    cardPayment(tx, "ALD-C000001", 990.00, device, "US", now())).getBody());
            if (first == null) first = last;
        }
        assertThat(first.get("riskScore").asDouble()).isGreaterThan(a.get("riskScore").asDouble());
        assertThat(first.get("reasons").toString()).contains("NEW_DEVICE");        // device unseen before
        assertThat(last.get("reasons").toString()).contains("HIGH_TRANSACTION_VELOCITY")
                .doesNotContain("\"ruleId\":\"DEV-002\"");                     // now a known device
        assertThat(last.get("decision").asString()).isNotEqualTo("APPROVE");
    }

    @Test
    void sameIdempotencyKeyReturnsTheSameDecision() throws Exception {
        String tx = id();
        String body = transfer(tx, "ALD-C000001", 120.00, "D-KNOWN-1", "B-FRIEND", now());
        var first = score(GATEWAY_KEY, tx, body);
        var second = score(GATEWAY_KEY, tx, body);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(second.getBody()).get("decisionId").asString())
                .isEqualTo(JSON.readTree(first.getBody()).get("decisionId").asString());
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(JSON.readTree(second.getBody()).get("idempotentReplay").asBoolean()).isTrue();
    }

    @Test
    void reusingAKeyWithADifferentBodyIsRejected() throws Exception {
        String tx = id();
        score(GATEWAY_KEY, tx, transfer(tx, "ALD-C000001", 120.00, "D-KNOWN-1", "B-FRIEND", now()));
        var res = score(GATEWAY_KEY, tx, transfer(tx, "ALD-C000001", 999.00, "D-KNOWN-1", "B-FRIEND", now()));
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(JSON.readTree(res.getBody()).get("code").asString()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void duplicateTransactionWithANewKeyIsAConflict() throws Exception {
        String tx = id();
        String body = transfer(tx, "ALD-C000001", 50.00, "D-KNOWN-1", "B-FRIEND", now());
        score(GATEWAY_KEY, tx, body);
        var res = score(GATEWAY_KEY, tx + "-retry", body);
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        JsonNode problem = JSON.readTree(res.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("DUPLICATE_TRANSACTION");
        assertThat(problem.get("details").get("existingDecisionId").asString()).isNotBlank();
    }

    @Test
    void validationErrorsAreReportedPerField() throws Exception {
        String tx = id();
        String bad = cardPayment(tx, "ALD-C000001", 10, "D1", "PT", now())
                .replace("\"currency\":\"EUR\"", "\"currency\":\"euro\"")
                .replace("\"cardToken\":\"tok_ALD-C000001\"", "\"cardToken\":\"4111111111111111\"");
        var res = score(GATEWAY_KEY, tx, bad);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        JsonNode problem = JSON.readTree(res.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("details").get("errors").toString()).contains("currency").contains("raw card numbers");
        assertThat(problem.get("correlationId").asString()).isNotBlank();
    }

    @Test
    void malformedJsonAndMissingIdempotencyKeyAre400() {
        assertThat(score(GATEWAY_KEY, id(), "{not json").getStatusCode().value()).isEqualTo(400);
        String tx = id();
        var res = score(GATEWAY_KEY, null, cardPayment(tx, "ALD-C000001", 10, "D1", "PT", now()));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(res.getBody()).contains("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    void authenticationAndAuthorisation() {
        String tx = id();
        String body = cardPayment(tx, "ALD-C000001", 10, "D1", "PT", now());
        assertThat(score("wrong-key", tx, body).getStatusCode().value()).isEqualTo(401);
        assertThat(score(ANALYST_KEY, tx, body).getStatusCode().value()).isEqualTo(403);
        var adminAsGateway = http.get().uri("/v1/admin/tenants/aldermoor-bank/strategies")
                .header("X-Api-Key", GATEWAY_KEY).retrieve().toEntity(String.class);
        assertThat(adminAsGateway.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        String tx = id();
        JsonNode created = JSON.readTree(score(GATEWAY_KEY, tx,
                cardPayment(tx, "ALD-C000001", 20, "D-KNOWN-1", "PT", now())).getBody());
        var ownTenant = http.get().uri("/v1/decisions/" + created.get("decisionId").asString())
                .header("X-Api-Key", ANALYST_KEY).retrieve().toEntity(String.class);
        var otherTenant = http.get().uri("/v1/decisions/" + created.get("decisionId").asString())
                .header("X-Api-Key", QUILLON_GATEWAY_KEY).retrieve().toEntity(String.class);
        assertThat(ownTenant.getStatusCode().value()).isEqualTo(200);
        assertThat(otherTenant.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void secondTenantUsesItsOwnStrategyAndModel() throws Exception {
        String tx = id();
        String body = cardPayment(tx, "QPY-C000001", 25, "D-Q-1", "ES", now()).replace("\"ipCountry\":\"PT\"", "\"ipCountry\":\"ES\"");
        JsonNode res = JSON.readTree(score(QUILLON_GATEWAY_KEY, tx, body).getBody());
        assertThat(res.get("versions").get("model").asString()).isEqualTo("quillon-pay-lgbm-1.0.0");
        assertThat(res.get("versions").get("challengerModel").asString()).isEqualTo("quillon-pay-lgbm-1.1.0");
    }

    @Test
    void unknownCustomerIsScoredWithConservativeProfile() throws Exception {
        String tx = id();
        JsonNode res = JSON.readTree(score(GATEWAY_KEY, tx,
                cardPayment(tx, "ALD-UNKNOWN-9", 20, "D-X", "PT", now())).getBody());
        assertThat(res.get("degradedModes").toString()).contains("PROFILE_UNAVAILABLE");
    }

    @Test
    void listingUsesKeysetPagination() throws Exception {
        for (int i = 0; i < 3; i++) {
            String tx = id();
            score(GATEWAY_KEY, tx, cardPayment(tx, "ALD-C000001", 15, "D-KNOWN-1", "PT", now()));
        }
        JsonNode page1 = JSON.readTree(http.get().uri("/v1/decisions?limit=2").header("X-Api-Key", ANALYST_KEY)
                .retrieve().body(String.class));
        assertThat(page1.get("items").size()).isEqualTo(2);
        String cursor = page1.get("nextCursor").asString();
        JsonNode page2 = JSON.readTree(http.get().uri("/v1/decisions?limit=2&cursor=" + cursor).header("X-Api-Key", ANALYST_KEY)
                .retrieve().body(String.class));
        assertThat(page2.get("items").get(0).get("decisionId").asString())
                .isNotEqualTo(page1.get("items").get(1).get("decisionId").asString());
    }

    @Test
    void healthExposesStrategyAndModelState() throws Exception {
        JsonNode health = JSON.readTree(http.get().uri("/actuator/health/readiness").retrieve().body(String.class));
        assertThat(health.get("status").asString()).isEqualTo("UP");
        String prometheus = http.get().uri("/actuator/prometheus").retrieve().body(String.class);
        assertThat(prometheus).contains("risk_decision_latency_seconds");
    }
}
