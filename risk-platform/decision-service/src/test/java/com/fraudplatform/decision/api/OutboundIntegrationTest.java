package com.fraudplatform.decision.api;

import com.fraudplatform.commons.integration.IntegrationException;
import com.fraudplatform.decision.IntegrationTestBase;
import com.fraudplatform.decision.application.CaseService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Outbound REST integrations against WireMock: CRM, device intelligence, case management, model-service. */
class OutboundIntegrationTest extends IntegrationTestBase {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final WireMockServer WM = new WireMockServer(options().dynamicPort());

    static {
        WM.start();
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry r) {
        for (String name : List.of("customer-profile", "device-risk", "case-management", "model-service")) {
            r.add("platform.integrations.endpoints." + name + ".enabled", () -> "true");
            r.add("platform.integrations.endpoints." + name + ".base-url", WM::baseUrl);
        }
        r.add("platform.integrations.endpoints.case-management.initial-backoff-ms", () -> "20");
    }

    @Autowired
    CaseService cases;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void reset() {
        WM.resetAll();
        WM.stubFor(post("/device-intel/v1/assessments").willReturn(okJson(
                "{\"assessmentId\":\"a1\",\"riskScore\":10,\"compromisedIp\":false,\"emulator\":false,\"proxy\":false,\"signals\":[]}")));
    }

    private JsonNode score(String body, String key) throws Exception {
        var res = http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", key)
                .body(body).retrieve().toEntity(String.class);
        assertThat(res.getStatusCode().value()).as(res.getBody()).isEqualTo(200);
        return JSON.readTree(res.getBody());
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS).toString();
    }

    @Test
    void customerMissingFromReplicaIsFetchedFromCrmAndCached() throws Exception {
        String customer = "ALD-NEW-" + UUID.randomUUID().toString().substring(0, 8);
        WM.stubFor(get(urlPathMatching("/crm/v1/tenants/aldermoor-bank/customers/.*")).willReturn(okJson("""
                {"customerId":"%s","segment":"premium","homeCountry":"PT","tenureDays":1200,"avgAmount90d":90.0,
                 "riskTier":"standard","boundDeviceIds":["D-CRM-1"]}""".formatted(customer))));
        String tx = "OUT-" + UUID.randomUUID();
        JsonNode r = score(cardPayment(tx, customer, 50, "D-CRM-1", "PT", now()), tx);
        assertThat(r.get("degradedModes").toString()).doesNotContain("PROFILE_UNAVAILABLE");
        assertThat(customers.find("aldermoor-bank", customer)).get().extracting(p -> p.segment()).isEqualTo("premium");
    }

    @Test
    void crmFailureDegradesButStillDecides() throws Exception {
        WM.stubFor(get(urlPathMatching("/crm/.*")).willReturn(aResponse().withStatus(500)));
        String tx = "OUT-" + UUID.randomUUID();
        JsonNode r = score(cardPayment(tx, "ALD-NOPE-" + UUID.randomUUID(), 50, "D1", "PT", now()), tx);
        assertThat(r.get("degradedModes").toString()).contains("PROFILE_UNAVAILABLE");
    }

    @Test
    void compromisedIpFromDeviceIntelligenceIsAReason() throws Exception {
        WM.stubFor(post("/device-intel/v1/assessments").willReturn(okJson(
                "{\"assessmentId\":\"a2\",\"riskScore\":90,\"compromisedIp\":true,\"emulator\":false,\"proxy\":false,\"signals\":[\"IP_ON_THREAT_FEED\"]}")));
        String tx = "OUT-" + UUID.randomUUID();
        JsonNode r = score(cardPayment(tx, "ALD-C000001", 40, "D-KNOWN-1", "PT", now()), tx);
        String full = http.get().uri("/v1/decisions/" + r.get("decisionId").asString()).header("X-Api-Key", ANALYST_KEY)
                .retrieve().body(String.class);
        assertThat(full).contains("COMPROMISED_IP").contains("DEVICE_INTELLIGENCE");
        WM.verify(postRequestedFor(urlEqualTo("/device-intel/v1/assessments")).withHeader("X-Correlation-Id", equalTo(r.get("correlationId").asString())));
    }

    @Test
    void slowDeviceVendorIsCutOffAtItsBudget() throws Exception {
        WM.stubFor(post("/device-intel/v1/assessments").willReturn(okJson("{}").withFixedDelay(500)));
        String tx = "OUT-" + UUID.randomUUID();
        long start = System.nanoTime();
        JsonNode r = score(cardPayment(tx, "ALD-C000001", 40, "D-KNOWN-1", "PT", now()), tx);
        assertThat(r.get("degradedModes").toString()).contains("DEVICE_RISK_UNAVAILABLE");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(400);
    }

    private UUID reviewDecision() throws Exception {
        String tx = "CASE-" + UUID.randomUUID();
        JsonNode r = score(transfer(tx, "ALD-C000001", 70, "D-KNOWN-1", "B-" + UUID.randomUUID(), now()), tx);
        return UUID.fromString(r.get("decisionId").asString());
    }

    @Test
    void caseCreationRetriesTransientFailuresAndIsIdempotent() throws Exception {
        UUID decisionId = reviewDecision();
        WM.stubFor(post("/cases/v1/cases").inScenario("cm").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        WM.stubFor(post("/cases/v1/cases").inScenario("cm").whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(504)).willSetStateTo("ok"));
        WM.stubFor(post("/cases/v1/cases").inScenario("cm").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"caseReference\":\"CM-777\",\"status\":\"OPEN\"}").withStatus(201)));

        var c = cases.openForReviewDecision("aldermoor-bank", decisionId, "t", "ALD-C000001", "HIGH", List.of("NEW_BENEFICIARY"));
        assertThat(c.status()).isEqualTo("OPEN");
        assertThat(c.externalCaseRef()).isEqualTo("CM-777");
        WM.verify(3, postRequestedFor(urlEqualTo("/cases/v1/cases")).withHeader("Idempotency-Key", equalTo(decisionId.toString())));

        // Duplicate event: no second external call, same case.
        var again = cases.openForReviewDecision("aldermoor-bank", decisionId, "t", "ALD-C000001", "HIGH", List.of());
        assertThat(again.caseId()).isEqualTo(c.caseId());
        WM.verify(3, postRequestedFor(urlEqualTo("/cases/v1/cases")));
    }

    @Test
    void permanentCaseFailureIsRecordedForSupport() throws Exception {
        UUID decisionId = reviewDecision();
        WM.stubFor(post("/cases/v1/cases").willReturn(aResponse().withStatus(400).withBody("{\"error\":\"priority invalid\"}")));
        assertThatThrownBy(() -> cases.openForReviewDecision("aldermoor-bank", decisionId, "t", "ALD-C000001", "HIGH", List.of()))
                .isInstanceOf(IntegrationException.class)
                .extracting(e -> ((IntegrationException) e).kind()).isEqualTo(IntegrationException.Kind.CLIENT_ERROR);
        WM.verify(1, postRequestedFor(urlEqualTo("/cases/v1/cases")));   // 4xx is never retried
        String failures = http.get().uri("/v1/cases/integration-failures").header("X-Api-Key", ANALYST_KEY).retrieve().body(String.class);
        assertThat(failures).contains(decisionId.toString()).contains("CLIENT_ERROR");
    }

    @Test
    void analystResolvesCaseAndLabelIsRecorded() throws Exception {
        UUID decisionId = reviewDecision();
        WM.stubFor(post("/cases/v1/cases").willReturn(okJson("{\"caseReference\":\"CM-9\",\"status\":\"OPEN\"}")));
        var c = cases.openForReviewDecision("aldermoor-bank", decisionId, "t", "ALD-C000001", "HIGH", List.of());
        var stale = http.post().uri("/v1/cases/" + c.caseId() + "/resolve").header("X-Api-Key", ANALYST_KEY)
                .header("If-Match", "999").body("{\"outcome\":\"CONFIRMED_FRAUD\",\"fraudType\":\"account_takeover\"}")
                .retrieve().toEntity(String.class);
        assertThat(stale.getStatusCode().value()).isEqualTo(409);
        JsonNode resolved = JSON.readTree(http.post().uri("/v1/cases/" + c.caseId() + "/resolve").header("X-Api-Key", ANALYST_KEY)
                .header("If-Match", Integer.toString(c.rowVersion()))
                .body("{\"outcome\":\"CONFIRMED_FRAUD\",\"fraudType\":\"account_takeover\",\"note\":\"customer confirmed\"}")
                .retrieve().body(String.class));
        assertThat(resolved.get("status").asString()).isEqualTo("CONFIRMED_FRAUD");
        String label = jdbc.sql("SELECT label FROM fraud_labels WHERE tenant_id = 'aldermoor-bank' AND transaction_id = 't' AND source = 'ANALYST'")
                .query(String.class).single();
        assertThat(label).isEqualTo("FRAUD");
    }

    @Test
    void explanationUsesModelServiceAndDegradesGracefully() throws Exception {
        String tx = "EXP-" + UUID.randomUUID();
        JsonNode r = score(cardPayment(tx, "ALD-C000001", 400, "D-KNOWN-1", "PT", now()), tx);
        String path = "/v1/decisions/" + r.get("decisionId").asString() + "/explanation";

        WM.stubFor(post("/v1/explanations").willReturn(okJson(
                "{\"probability\":0.12,\"baseValueLogOdds\":-6.1,\"contributions\":[{\"feature\":\"amount_to_baseline\",\"contribution\":1.9}],\"topReasons\":[]}")));
        JsonNode ok = JSON.readTree(http.get().uri(path).header("X-Api-Key", ANALYST_KEY).retrieve().body(String.class));
        assertThat(ok.get("shapAvailable").asBoolean()).isTrue();
        assertThat(ok.get("shap").get("contributions").get(0).get("feature").asString()).isEqualTo("amount_to_baseline");

        WM.stubFor(post("/v1/explanations").willReturn(aResponse().withStatus(503)));
        JsonNode degraded = JSON.readTree(http.get().uri(path).header("X-Api-Key", ANALYST_KEY).retrieve().body(String.class));
        assertThat(degraded.get("shapAvailable").asBoolean()).isFalse();
        assertThat(degraded.get("note").asString()).contains("SERVER_ERROR");
        assertThat(degraded.get("decisionReasons").isArray()).isTrue();
    }
}
