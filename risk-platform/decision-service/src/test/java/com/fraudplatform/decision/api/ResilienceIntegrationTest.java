package com.fraudplatform.decision.api;

import com.fraudplatform.decision.IntegrationTestBase;
import com.fraudplatform.decision.lab.FaultInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure injection: dependencies fail, the API still answers with a documented degraded decision
 * (never a 5xx for valid input) and says so in {@code degradedModes} and the reasons.
 */
@ActiveProfiles("lab")
class ResilienceIntegrationTest extends IntegrationTestBase {

    static final ObjectMapper JSON = JsonMapper.builder().build();

    @Autowired
    FaultInjector faults;

    @AfterEach
    void clearFaults() {
        faults.clear();
    }

    private JsonNode score(String body, String key) throws Exception {
        var res = http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", key)
                .body(body).retrieve().toEntity(String.class);
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        return JSON.readTree(res.getBody());
    }

    @Test
    void modelFailureFallsBackToChannelPolicy() throws Exception {
        faults.set(FaultInjector.Point.MODEL_INFERENCE, new FaultInjector.Fault(0, 1.0));
        String tx = "RES-" + UUID.randomUUID();
        // MOBILE transfer: Aldermoor's fail mode for MOBILE is REVIEW.
        JsonNode res = score(transfer(tx, "ALD-C000001", 80, "D-KNOWN-1", "B-FRIEND",
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()), tx);
        assertThat(res.get("degradedModes").toString()).contains("MODEL_UNAVAILABLE");
        assertThat(res.get("decision").asString()).isEqualTo("REVIEW");
        assertThat(res.get("reasons").toString()).contains("MODEL_UNAVAILABLE_FALLBACK");
        assertThat(res.path("fraudProbability").isMissingNode() || res.path("fraudProbability").isNull()).as(res.toString()).isTrue();
        assertThat(res.get("versions").path("model").isMissingNode() || res.get("versions").path("model").isNull()).isTrue();
    }

    @Test
    void slowModelIsCutOffByTheTimeBudget() throws Exception {
        faults.set(FaultInjector.Point.MODEL_INFERENCE, new FaultInjector.Fault(500, 0));
        String tx = "RES-" + UUID.randomUUID();
        long start = System.nanoTime();
        JsonNode res = score(cardPayment(tx, "ALD-C000001", 20, "D-KNOWN-1", "PT",
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()), tx);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(res.get("degradedModes").toString()).contains("MODEL_UNAVAILABLE");
        assertThat(elapsedMs).isLessThan(450);   // budget 25 ms, not the injected 500 ms
    }

    @Test
    void redisFailureUsesPostgresFallback() throws Exception {
        faults.set(FaultInjector.Point.FEATURE_STORE, new FaultInjector.Fault(0, 1.0));
        String tx = "RES-" + UUID.randomUUID();
        JsonNode res = score(cardPayment(tx, "ALD-C000001", 20, "D-KNOWN-1", "PT",
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()), tx);
        assertThat(res.get("degradedModes").toString()).contains("FEATURE_STORE_DEGRADED");
        assertThat(res.get("reasons").toString()).contains("DEGRADED_SIGNALS");
        assertThat(res.get("versions").get("model").asString()).isEqualTo("aldermoor-bank-lgbm-1.0.0");
    }
}
