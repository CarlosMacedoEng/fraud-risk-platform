package com.fraudplatform.decision.strategy;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.domain.ReasonCode;
import com.fraudplatform.decision.features.GraphRisk;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class StrategyEngineTest {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String DOC = """
            {"schemaVersion":1,"customerId":"t","version":"1.0.0","model":{"version":"m-1"},
             "weights":{"model":1.0,"rules":0.5,"graph":0.6,"anomaly":0.4},
             "anomaly":{"tailStartPercentile":0.99},
             "thresholds":{"default":{"review":0.4,"decline":0.8},"byChannel":{"BRANCH":{"review":0.7}}},
             "channelPolicies":{"POS":{"failMode":"APPROVE","maxFailOpenAmount":100},"MOBILE":{"failMode":"REVIEW"},
                                "WEB":{"failMode":"DECLINE"}},
             "lists":{"blocked":["D-BAD"]},
             "emergencyRules":[{"id":"E1","action":"DECLINE","reasonCode":"BLOCKED_ENTITY",
                                "when":{"field":"device_id","op":"in_list","value":"blocked"}}],
             "rules":[
               {"id":"R1","points":40,"reasonCode":"NEW_DEVICE","when":{"field":"is_new_device","op":"eq","value":1}},
               {"id":"R2","points":80,"reasonCode":"HIGH_TRANSACTION_VELOCITY","channels":["ECOM"],
                "when":{"field":"card_txn_count_10m","op":"gte","value":3}},
               {"id":"P1","action":"REVIEW","reasonCode":"NEW_BENEFICIARY",
                "when":{"all":[{"field":"is_new_beneficiary","op":"eq","value":1},{"field":"amount","op":"gte","value":5000}]}}]}
            """;

    final CompiledStrategy strategy = new StrategyCompiler().compile("t", JSON.readTree(DOC));
    final StrategyEngine engine = new StrategyEngine();

    static EvaluationContext ctx(Map<String, Object> values) {
        EvaluationContext c = new EvaluationContext();
        values.forEach(c::put);
        return c;
    }

    static StrategyEngine.Signals signals(Double p, Double anomaly, double graph, double amount, Channel ch) {
        return new StrategyEngine.Signals(p, anomaly, new GraphRisk(graph, 0, 0, 0, Map.of("device", "shared device"), true),
                amount, ch, "retail", null, false);
    }

    @Test
    void noisyOrCombinesAllSignals() {
        var out = engine.evaluate(strategy, ctx(Map.of("is_new_device", 1.0)), signals(0.3, 0.995, 0.5, 50, Channel.ECOM));
        double expected = 1 - (1 - 0.3) * (1 - 0.5 * 0.4) * (1 - 0.6 * 0.5) * (1 - 0.4 * 0.5);
        assertThat(out.riskScore()).isCloseTo(expected, within(1e-12));
        assertThat(out.decision()).isEqualTo(Decision.REVIEW);
        assertThat(out.reasons().getFirst().code()).isEqualTo(ReasonCode.HIGH_MODEL_SCORE);
        assertThat(out.reasons()).extracting(r -> r.code()).contains(ReasonCode.GRAPH_RISK, ReasonCode.ANOMALOUS_PATTERN, ReasonCode.NEW_DEVICE);
    }

    @Test
    void channelRestrictedRuleOnlyFiresOnItsChannel() {
        var ecom = engine.evaluate(strategy, ctx(Map.of("card_txn_count_10m", 4.0)), signals(0.0, 0.5, 0, 10, Channel.ECOM));
        var pos = engine.evaluate(strategy, ctx(Map.of("card_txn_count_10m", 4.0)), signals(0.0, 0.5, 0, 10, Channel.POS));
        assertThat(ecom.rulesHit()).contains("R2");
        assertThat(pos.rulesHit()).doesNotContain("R2");
    }

    @Test
    void policyAndEmergencyRulesSetMinimumDecision() {
        var review = engine.evaluate(strategy, ctx(Map.of("is_new_beneficiary", 1.0, "amount", 6000.0)),
                signals(0.01, 0.1, 0, 6000, Channel.MOBILE));
        assertThat(review.decision()).isEqualTo(Decision.REVIEW);
        var decline = engine.evaluate(strategy, ctx(Map.of("device_id", "D-BAD")), signals(0.01, 0.1, 0, 10, Channel.ECOM));
        assertThat(decline.decision()).isEqualTo(Decision.DECLINE);
        assertThat(decline.reasons().getFirst().code()).isEqualTo(ReasonCode.BLOCKED_ENTITY);
    }

    @Test
    void channelThresholdOverrideApplies() {
        var branch = engine.evaluate(strategy, ctx(Map.of()), signals(0.5, 0.1, 0, 10, Channel.BRANCH));
        var web = engine.evaluate(strategy, ctx(Map.of()), signals(0.5, 0.1, 0, 10, Channel.WEB));
        assertThat(branch.decision()).isEqualTo(Decision.APPROVE);
        assertThat(web.decision()).isEqualTo(Decision.REVIEW);
    }

    @Test
    void fallbackPolicyWhenModelUnavailable() {
        var smallPos = engine.evaluate(strategy, ctx(Map.of()), signals(null, null, 0, 50, Channel.POS));
        var largePos = engine.evaluate(strategy, ctx(Map.of()), signals(null, null, 0, 500, Channel.POS));
        var mobile = engine.evaluate(strategy, ctx(Map.of()), signals(null, null, 0, 50, Channel.MOBILE));
        var web = engine.evaluate(strategy, ctx(Map.of()), signals(null, null, 0, 50, Channel.WEB));
        assertThat(smallPos.decision()).isEqualTo(Decision.APPROVE);
        assertThat(largePos.decision()).isEqualTo(Decision.REVIEW);
        assertThat(mobile.decision()).isEqualTo(Decision.REVIEW);
        assertThat(web.decision()).isEqualTo(Decision.DECLINE);
        assertThat(smallPos.fallbackApplied()).isTrue();
        assertThat(smallPos.reasons()).extracting(r -> r.code()).contains(ReasonCode.MODEL_UNAVAILABLE_FALLBACK);
    }

    @Test
    void missingFieldsNeverMatch() {
        var out = engine.evaluate(strategy, ctx(Map.of()), signals(0.0, 0.1, 0, 10, Channel.ECOM));
        assertThat(out.rulesHit()).isEmpty();
        assertThat(out.decision()).isEqualTo(Decision.APPROVE);
    }
}
