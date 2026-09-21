package com.fraudplatform.decision.api;

import com.fraudplatform.decision.IntegrationTestBase;
import com.fraudplatform.decision.TestPaths;
import com.fraudplatform.decision.application.ActiveStrategyProvider;
import com.fraudplatform.decision.domain.CustomerProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** End-to-end configuration governance on Aldermoor: draft → four-eyes → promotion → canary → rollback → emergency. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StrategyGovernanceIntegrationTest extends IntegrationTestBase {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String BASE = "/v1/admin/tenants/aldermoor-bank";
    static final String APPROVER_KEY = "dev-aldermoor-approver-key";

    private ResponseEntity<String> call(String method, String path, String key, String body, Integer ifMatch) {
        var spec = http.method(org.springframework.http.HttpMethod.valueOf(method)).uri(BASE + path).header("X-Api-Key", key);
        if (ifMatch != null) spec = spec.header("If-Match", ifMatch.toString());
        if (body != null) spec = spec.body(body);
        return spec.retrieve().toEntity(String.class);
    }

    private JsonNode ok(ResponseEntity<String> res) throws Exception {
        assertThat(res.getStatusCode().is2xxSuccessful()).as(res.getBody()).isTrue();
        return JSON.readTree(res.getBody());
    }

    private String draftFrom110(String version, double reviewThreshold) throws Exception {
        ObjectNode doc = (ObjectNode) JSON.readTree(Files.readString(
                TestPaths.CONFIG.resolve("customers/aldermoor-bank/strategies/1.1.0.json")));
        doc.put("version", version);
        doc.put("changeSummary", "test: review threshold " + reviewThreshold);
        ((ObjectNode) doc.get("thresholds").get("default")).put("review", reviewThreshold);
        return doc.toString();
    }

    private JsonNode deployment(String env) throws Exception {
        for (JsonNode d : ok(call("GET", "/deployments", ADMIN_KEY, null, null))) {
            if (d.get("environment").asString().equals(env)) return d;
        }
        return null;
    }

    private void scoreTraffic(int customersCount) {
        for (int i = 0; i < customersCount; i++) {
            String customer = "ALD-GOV-" + i;
            customers.upsert("aldermoor-bank", new CustomerProfile(customer, "retail", "PT", 500, 40, "standard", Set.of(), false), "BATCH");
            String tx = "GOV-" + UUID.randomUUID();
            http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", tx)
                    .body(cardPayment(tx, customer, 60 + i, "D-GOV-" + i, "PT", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                    .retrieve().toEntity(String.class);
        }
    }

    @AfterEach
    void restoreDev() throws Exception {
        JsonNode dev = deployment("dev");
        if (dev != null && (!dev.get("activeVersion").asString().equals("1.1.0") || !dev.get("candidateVersion").isNull())) {
            ok(call("POST", "/deployments/dev/promote", ADMIN_KEY, "{\"version\":\"1.1.0\",\"reason\":\"test cleanup\"}", null));
        }
    }

    @Test
    @Order(1)
    void fullLifecycleWithFourEyesPromotionOrderCanaryAndRollback() throws Exception {
        // Draft by one admin.
        JsonNode draft = ok(call("POST", "/strategies", ADMIN_KEY, draftFrom110("1.2.0", 0.35), null));
        assertThat(draft.get("version").get("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.get("validation").get("errors").size()).isZero();

        // Four-eyes: the author cannot approve.
        var self = call("POST", "/strategies/1.2.0/approve", ADMIN_KEY, "{}", null);
        assertThat(self.getStatusCode().value()).isEqualTo(403);

        // A second admin approves.
        JsonNode approved = ok(call("POST", "/strategies/1.2.0/approve", APPROVER_KEY, "{\"comment\":\"reviewed impact\"}", null));
        assertThat(approved.get("version").get("status").asString()).isEqualTo("VALIDATED");
        assertThat(approved.get("version").get("validatedBy").asString()).isEqualTo("aldermoor-approver");

        // Promotion order: staging before dev is refused.
        var skip = call("POST", "/deployments/staging/promote", ADMIN_KEY, "{\"version\":\"1.2.0\"}", null);
        assertThat(skip.getStatusCode().value()).isEqualTo(422);
        assertThat(skip.getBody()).contains("promotion order");

        // Impact simulation on recent traffic.
        scoreTraffic(40);
        JsonNode sim = ok(call("POST", "/strategies/1.2.0/simulate?limit=500", ADMIN_KEY, null, null));
        assertThat(sim.get("evaluated").asInt()).isGreaterThan(30);
        assertThat(sim.get("activeVersion").asString()).isEqualTo("1.1.0");
        assertThat(sim.get("reviewRateCandidate").asDouble()).isGreaterThanOrEqualTo(sim.get("reviewRateActive").asDouble());

        // Canary 50% in dev with optimistic locking.
        int row = deployment("dev").get("rowVersion").asInt();
        var stale = call("POST", "/deployments/dev/promote", ADMIN_KEY, "{\"version\":\"1.2.0\",\"rolloutPercentage\":50}", row - 1 + 100);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        JsonNode canary = ok(call("POST", "/deployments/dev/promote", ADMIN_KEY,
                "{\"version\":\"1.2.0\",\"rolloutPercentage\":50,\"reason\":\"canary\"}", row));
        assertThat(canary.get("candidateVersion").asString()).isEqualTo("1.2.0");
        assertThat(canary.get("rolloutPercentage").asInt()).isEqualTo(50);

        // Traffic splits by customer bucket, consistently.
        Map<String, Set<String>> versionsByCustomer = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 30; i++) {
                String customer = "ALD-GOV-" + i;
                String tx = "CAN-" + UUID.randomUUID();
                JsonNode r = ok(http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", tx)
                        .body(cardPayment(tx, customer, 25, "D-GOV-" + i, "PT", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                        .retrieve().toEntity(String.class));
                String version = r.get("versions").get("strategy").asString();
                versionsByCustomer.computeIfAbsent(customer, k -> new HashSet<>()).add(version);
                seen.add(version);
                String expected = ActiveStrategyProvider.bucket("aldermoor-bank", customer) < 50 ? "1.2.0" : "1.1.0";
                assertThat(version).isEqualTo(expected);
            }
        }
        assertThat(seen).containsExactlyInAnyOrder("1.1.0", "1.2.0");
        assertThat(versionsByCustomer.values()).allMatch(s -> s.size() == 1);

        // Complete the rollout, then roll back one step.
        JsonNode full = ok(call("POST", "/deployments/dev/promote", ADMIN_KEY, "{\"version\":\"1.2.0\",\"reason\":\"canary healthy\"}", null));
        assertThat(full.get("activeVersion").asString()).isEqualTo("1.2.0");
        assertThat(full.get("previousVersion").asString()).isEqualTo("1.1.0");
        JsonNode back = ok(call("POST", "/deployments/dev/rollback", ADMIN_KEY, "{\"reason\":\"test\"}", full.get("rowVersion").asInt()));
        assertThat(back.get("activeVersion").asString()).isEqualTo("1.1.0");

        // Everything is in the audit trail.
        JsonNode trail = ok(call("GET", "/audit?limit=50", ADMIN_KEY, null, null));
        Set<String> actions = new HashSet<>();
        trail.forEach(a -> actions.add(a.get("action").asString()));
        assertThat(actions).contains("STRATEGY_DRAFT_CREATED", "STRATEGY_APPROVED", "STRATEGY_CANARY",
                "STRATEGY_ROLLOUT_COMPLETED", "STRATEGY_ROLLBACK");
    }

    @Test
    @Order(2)
    void invalidDraftCannotBeApproved() throws Exception {
        String bad = draftFrom110("1.3.0", 0.95);   // review above decline
        JsonNode draft = ok(call("POST", "/strategies", ADMIN_KEY, bad, null));
        assertThat(draft.get("validation").get("errors").toString()).contains("review must be lower than decline");
        var res = call("POST", "/strategies/1.3.0/approve", APPROVER_KEY, "{}", null);
        assertThat(res.getStatusCode().value()).isEqualTo(422);
        assertThat(JSON.readTree(res.getBody()).get("code").asString()).isEqualTo("STRATEGY_INVALID");
        // Versions are immutable once created.
        assertThat(call("POST", "/strategies", ADMIN_KEY, bad, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @Order(3)
    void emergencyBlockSkipsPromotionOrderWithReason() throws Exception {
        String derive = """
                {"newVersion":"1.1.1","changeSummary":"EMERGENCY: block compromised device","emergency":true,
                 "listAdditions":{"blockedDevices":["D-COMPROMISED-1"]}}
                """;
        ok(call("POST", "/strategies/1.1.0/derive", ADMIN_KEY, derive, null));
        ok(call("POST", "/strategies/1.1.1/approve", APPROVER_KEY, "{\"comment\":\"incident INC-042\"}", null));
        var noReason = call("POST", "/deployments/dev/promote", ADMIN_KEY, "{\"version\":\"1.1.1\"}", null);
        assertThat(noReason.getStatusCode().value()).isEqualTo(400);
        JsonNode prod = ok(call("POST", "/deployments/prod/promote", ADMIN_KEY,
                "{\"version\":\"1.1.1\",\"reason\":\"INC-042 active device compromise\"}", null));
        assertThat(prod.get("activeVersion").asString()).isEqualTo("1.1.1");

        // In dev (this service's environment) the emergency version blocks the device immediately.
        ok(call("POST", "/deployments/dev/promote", ADMIN_KEY, "{\"version\":\"1.1.1\",\"reason\":\"INC-042\"}", null));
        String tx = "EMR-" + UUID.randomUUID();
        JsonNode r = ok(http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", tx)
                .body(cardPayment(tx, "ALD-C000001", 5, "D-COMPROMISED-1", "PT", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                .retrieve().toEntity(String.class));
        assertThat(r.get("decision").asString()).isEqualTo("DECLINE");
        assertThat(r.get("reasons").get(0).get("code").asString()).isEqualTo("BLOCKED_ENTITY");
    }

    @Test
    @Order(4)
    void modelGovernance() throws Exception {
        JsonNode models = ok(call("GET", "/models", ADMIN_KEY, null, null));
        assertThat(models.toString()).contains("aldermoor-bank-lgbm-1.0.0").contains("CHAMPION");

        JsonNode reg = ok(call("POST", "/models/aldermoor-bank-lgbm-1.1.0/register", ADMIN_KEY, null, null));
        assertThat(reg.get("status").asString()).isEqualTo("CANDIDATE");
        var jump = call("POST", "/models/aldermoor-bank-lgbm-1.1.0/status", ADMIN_KEY, "{\"status\":\"CHAMPION\"}", null);
        assertThat(jump.getStatusCode().value()).isEqualTo(422);
        ok(call("POST", "/models/aldermoor-bank-lgbm-1.1.0/status", ADMIN_KEY,
                "{\"status\":\"RETIRED\",\"reason\":\"failed validation gate (PR-AUC 0.649 < 0.768)\"}", null));

        ObjectNode doc = (ObjectNode) JSON.readTree(draftFrom110("1.4.0", 0.4));
        ((ObjectNode) doc.get("model")).put("version", "aldermoor-bank-lgbm-1.1.0");
        JsonNode draft = ok(call("POST", "/strategies", ADMIN_KEY, doc.toString(), null));
        assertThat(draft.get("validation").get("errors").toString()).contains("RETIRED");
    }

    @Test
    @Order(5)
    void adminCredentialsAreTenantScoped() {
        var res = call("GET", "/strategies", QUILLON_ADMIN_KEY, null, null);
        assertThat(res.getStatusCode().value()).isEqualTo(403);
    }
}
