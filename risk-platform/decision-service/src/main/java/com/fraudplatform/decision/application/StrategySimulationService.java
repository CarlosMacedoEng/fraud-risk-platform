package com.fraudplatform.decision.application;

import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.features.FeatureVector;
import com.fraudplatform.decision.features.GraphRisk;
import com.fraudplatform.decision.inference.ModelRegistry;
import com.fraudplatform.decision.inference.OnnxModelBundle;
import com.fraudplatform.decision.persistence.SimulationQueries;
import com.fraudplatform.decision.strategy.CompiledStrategy;
import com.fraudplatform.decision.strategy.EvaluationContext;
import com.fraudplatform.decision.strategy.StrategyEngine;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What-if replay: re-decides recent real traffic with the candidate strategy and with the currently
 * active strategy, using the exact stored inputs (feature vectors). If the candidate uses another model
 * version the stored feature vectors are rescored with it. Answers the questions a risk analyst must
 * answer before approving a change: "how many decisions change, in which direction, and on which traffic?"
 */
@Service
public class StrategySimulationService {

    public record Change(String transactionId, String channel, double amount, String from, String to,
                         double activeScore, double candidateScore, String topReason) {
    }

    public record Report(String activeVersion, String candidateVersion, int evaluated, int skipped,
                         Map<String, Map<String, Integer>> transitions, Map<String, Integer> activeCounts,
                         Map<String, Integer> candidateCounts, double reviewRateActive, double reviewRateCandidate,
                         double declineRateActive, double declineRateCandidate, Map<String, Integer> labelledOutcome,
                         List<Change> sampleChanges) {
    }

    private final SimulationQueries queries;
    private final ModelRegistry models;
    private final StrategyEngine engine = new StrategyEngine();

    public StrategySimulationService(SimulationQueries queries, ModelRegistry models) {
        this.queries = queries;
        this.models = models;
    }

    public Report simulate(String tenant, CompiledStrategy active, CompiledStrategy candidate, int limit) {
        Map<Decision, Map<Decision, Integer>> matrix = new EnumMap<>(Decision.class);
        Map<Decision, Integer> activeCounts = new EnumMap<>(Decision.class);
        Map<Decision, Integer> candidateCounts = new EnumMap<>(Decision.class);
        Map<String, Integer> labelled = new LinkedHashMap<>();
        List<Change> changes = new ArrayList<>();
        int evaluated = 0;
        int skipped = 0;
        for (SimulationQueries.Row row : queries.recent(tenant, limit)) {
            var a = decide(active, row);
            var c = decide(candidate, row);
            if (a == null || c == null) {
                skipped++;
                continue;
            }
            evaluated++;
            matrix.computeIfAbsent(a.decision(), k -> new EnumMap<>(Decision.class)).merge(c.decision(), 1, Integer::sum);
            activeCounts.merge(a.decision(), 1, Integer::sum);
            candidateCounts.merge(c.decision(), 1, Integer::sum);
            if (row.fraudLabel() != null) {
                labelled.merge(row.fraudLabel() + "->" + c.decision(), 1, Integer::sum);
            }
            if (a.decision() != c.decision() && changes.size() < 25) {
                changes.add(new Change(row.transaction().transactionId(), row.transaction().channel().name(),
                        row.transaction().amount().doubleValue(), a.decision().name(), c.decision().name(),
                        round(a.riskScore()), round(c.riskScore()),
                        c.reasons().isEmpty() ? null : c.reasons().getFirst().code().name()));
            }
        }
        Map<String, Map<String, Integer>> transitions = new LinkedHashMap<>();
        matrix.forEach((from, tos) -> {
            Map<String, Integer> m = new LinkedHashMap<>();
            tos.forEach((to, n) -> m.put(to.name(), n));
            transitions.put(from.name(), m);
        });
        int n = Math.max(evaluated, 1);
        return new Report(active.version(), candidate.version(), evaluated, skipped, transitions, names(activeCounts),
                names(candidateCounts), rate(activeCounts, Decision.REVIEW, n), rate(candidateCounts, Decision.REVIEW, n),
                rate(activeCounts, Decision.DECLINE, n), rate(candidateCounts, Decision.DECLINE, n), labelled, changes);
    }

    private StrategyEngine.Outcome decide(CompiledStrategy s, SimulationQueries.Row row) {
        Double p = row.modelProbability();
        Double anomaly = row.anomalyPercentile();
        FeatureVector fv = new FeatureVector(row.features());
        if (!s.modelVersion().equals(row.modelVersion())) {
            var bundle = models.get(row.transaction().tenantId(), s.modelVersion());
            if (bundle.isEmpty()) return null;
            try {
                OnnxModelBundle.Scores scores = bundle.get().score(fv);
                p = scores.probability();
                anomaly = scores.anomalyPercentile();
            } catch (Exception e) {
                return null;
            }
        }
        if (p == null) return null; // decision was made without a model: not comparable
        var f = row.features();
        GraphRisk graph = new GraphRisk(f.getOrDefault("graph_device_risk", 0.0), f.getOrDefault("graph_beneficiary_risk", 0.0),
                f.getOrDefault("graph_merchant_risk", 0.0), f.getOrDefault("graph_account_risk", 0.0), Map.of(), true);
        var t = row.transaction();
        EvaluationContext ctx = new EvaluationContext();
        f.forEach(ctx::put);
        ctx.put("amount", t.amount().doubleValue()).put("channel", t.channel().name()).put("transaction_type", t.type().name())
                .put("mcc", t.mcc()).put("merchant_country", t.merchantCountry()).put("ip_country", t.ipCountry())
                .put("beneficiary_country", t.beneficiaryCountry()).put("device_id", t.deviceId())
                .put("card_token", t.cardToken()).put("merchant_id", t.merchantId()).put("beneficiary_id", t.beneficiaryId())
                .put("customer_segment", row.segment()).put("customer_id", t.customerId()).put("currency", t.currency())
                .put("graph_risk", graph.max()).put("model_probability", p).put("anomaly_percentile", anomaly);
        return engine.evaluate(s, ctx, new StrategyEngine.Signals(p, anomaly, graph, t.amount().doubleValue(), t.channel(),
                row.segment(), null, false));
    }

    private static Map<String, Integer> names(Map<Decision, Integer> m) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Decision d : Decision.values()) out.put(d.name(), m.getOrDefault(d, 0));
        return out;
    }

    private static double rate(Map<Decision, Integer> m, Decision d, int n) {
        return round(m.getOrDefault(d, 0) / (double) n);
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }
}
