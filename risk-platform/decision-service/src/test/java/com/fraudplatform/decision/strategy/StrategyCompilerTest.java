package com.fraudplatform.decision.strategy;

import com.fraudplatform.decision.TestPaths;
import com.fraudplatform.decision.domain.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyCompilerTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    final StrategyCompiler compiler = new StrategyCompiler();

    static JsonNode file(String tenant, String version) throws Exception {
        return JSON.readTree(Files.readString(TestPaths.CONFIG.resolve("customers").resolve(tenant)
                .resolve("strategies").resolve(version + ".json")));
    }

    @ParameterizedTest
    @CsvSource({"aldermoor-bank,1.0.0", "aldermoor-bank,1.1.0", "quillon-pay,1.0.0", "quillon-pay,1.1.0"})
    void versionControlledStrategiesAreValid(String tenant, String version) throws Exception {
        CompiledStrategy s = compiler.compile(tenant, file(tenant, version));
        assertThat(s.version()).isEqualTo(version);
        assertThat(s.rules()).isNotEmpty();
        assertThat(s.checksum()).hasSize(64);
    }

    @Test
    void disabledRulesAreNotCompiled() throws Exception {
        CompiledStrategy s = compiler.compile("aldermoor-bank", file("aldermoor-bank", "1.1.0"));
        assertThat(s.rules()).noneMatch(r -> r.id().equals("BEH-002"));
    }

    @Test
    void thresholdPrecedenceIsDefaultThenSegmentThenChannel() throws Exception {
        CompiledStrategy s = compiler.compile("aldermoor-bank", file("aldermoor-bank", "1.1.0"));
        assertThat(s.thresholdsFor("retail", Channel.ECOM).review()).isEqualTo(0.40);
        assertThat(s.thresholdsFor("premium", Channel.ECOM).review()).isEqualTo(0.50);
        assertThat(s.thresholdsFor("premium", Channel.BRANCH).review()).isEqualTo(0.60);
        assertThat(s.thresholdsFor("premium", Channel.BRANCH).decline()).isEqualTo(0.97);
    }

    @Test
    void reportsEveryProblemAtOnce() throws Exception {
        ObjectNode doc = (ObjectNode) file("aldermoor-bank", "1.1.0");
        doc.put("version", "v2");
        ((ObjectNode) doc.get("thresholds").get("default")).put("review", 0.95);
        ((ObjectNode) doc.get("weights")).put("graph", 1.5);
        ObjectNode badRule = JSON.createObjectNode();
        badRule.put("id", "BAD-1").put("points", 10).put("reasonCode", "NOT_A_CODE");
        badRule.set("when", JSON.readTree("{\"field\":\"merchant_country\",\"op\":\"gt\",\"value\":3}"));
        ((tools.jackson.databind.node.ArrayNode) doc.get("rules")).add(badRule);
        ObjectNode unknownList = JSON.createObjectNode();
        unknownList.put("id", "BAD-2").put("action", "DECLINE").put("reasonCode", "BLOCKED_ENTITY");
        unknownList.set("when", JSON.readTree("{\"field\":\"device_id\",\"op\":\"in_list\",\"value\":\"noSuchList\"}"));
        ((tools.jackson.databind.node.ArrayNode) doc.get("rules")).add(unknownList);

        List<String> errors = compiler.validate("aldermoor-bank", doc);
        assertThat(errors).anyMatch(e -> e.contains("version must be semantic"))
                .anyMatch(e -> e.contains("review must be lower than decline"))
                .anyMatch(e -> e.contains("weights.graph must be in [0, 1]"))
                .anyMatch(e -> e.contains("unknown reasonCode 'NOT_A_CODE'"))
                .anyMatch(e -> e.contains("needs a numeric field"))
                .anyMatch(e -> e.contains("list 'noSuchList' is not defined"));
        assertThatThrownBy(() -> compiler.compile("aldermoor-bank", doc)).isInstanceOf(StrategyValidationException.class);
    }

    @Test
    void rejectsStrategyForAnotherTenantAndUnknownFields() throws Exception {
        ObjectNode doc = (ObjectNode) file("quillon-pay", "1.1.0");
        ((ObjectNode) doc.get("rules").get(0).get("when").get("any").get(0)).put("field", "password");
        List<String> errors = compiler.validate("aldermoor-bank", doc);
        assertThat(errors).anyMatch(e -> e.contains("does not match tenant"))
                .anyMatch(e -> e.contains("unknown field 'password'"));
    }
}
