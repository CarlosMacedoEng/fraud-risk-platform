package com.fraudplatform.decision.api;

import com.fraudplatform.decision.IntegrationTestBase;
import com.fraudplatform.decision.TestPaths;
import com.fraudplatform.decision.domain.DegradedMode;
import com.fraudplatform.decision.domain.ReasonCode;
import com.fraudplatform.commons.error.ErrorCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-side contract test: live responses must satisfy the published OpenAPI document
 * (docs/api/openapi-v1.yaml), and the enums in the document must match the code. Prevents the spec and
 * the implementation from drifting apart silently.
 */
class OpenApiContractTest extends IntegrationTestBase {

    static Map<String, Object> schemas;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadSpec() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(TestPaths.REPO.resolve("docs/api/openapi-v1.yaml")));
        schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");
    }

    @SuppressWarnings("unchecked")
    static List<String> required(String schema) {
        return (List<String>) ((Map<String, Object>) schemas.get(schema)).get("required");
    }

    @SuppressWarnings("unchecked")
    static List<String> enumOf(String schema, String property) {
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) schemas.get(schema)).get("properties");
        Map<String, Object> p = (Map<String, Object>) props.get(property);
        if (p.containsKey("items")) p = (Map<String, Object>) p.get("items");
        return (List<String>) p.get("enum");
    }

    @Test
    void scoreResponseSatisfiesTheSpec() throws Exception {
        String tx = "CT-" + UUID.randomUUID();
        var res = http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).header("Idempotency-Key", tx)
                .body(cardPayment(tx, "ALD-C000001", 42, "D-KNOWN-1", "PT", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString()))
                .retrieve().toEntity(String.class);
        JsonNode body = JsonMapper.builder().build().readTree(res.getBody());
        for (String field : required("ScoreResponse")) {
            assertThat(body.has(field)).as("ScoreResponse.%s", field).isTrue();
        }
        for (JsonNode reason : body.get("reasons")) {
            required("Reason").forEach(f -> assertThat(reason.has(f)).as("Reason.%s", f).isTrue());
            assertThat(enumOf("Reason", "code")).contains(reason.get("code").asString());
        }
        assertThat(body.get("reasons").size()).isLessThanOrEqualTo(5);
        assertThat(enumOf("ScoreResponse", "decision")).contains(body.get("decision").asString());
    }

    @Test
    void problemResponsesSatisfyTheSpec() throws Exception {
        var res = http.post().uri("/v1/decisions").header("X-Api-Key", GATEWAY_KEY).body("{}").retrieve().toEntity(String.class);
        JsonNode problem = JsonMapper.builder().build().readTree(res.getBody());
        for (String field : required("Problem")) {
            assertThat(problem.has(field)).as("Problem.%s", field).isTrue();
        }
        assertThat(res.getHeaders().getContentType().toString()).contains("json");
    }

    @Test
    void publishedEnumsMatchTheCode() {
        assertThat(enumOf("Reason", "code")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ReasonCode.values()).map(Enum::name).toList());
        assertThat(enumOf("ScoreResponse", "degradedModes")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(DegradedMode.values()).map(Enum::name).toList());
        assertThat(enumOf("Problem", "code")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }
}
