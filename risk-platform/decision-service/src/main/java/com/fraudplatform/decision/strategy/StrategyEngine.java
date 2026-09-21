package com.fraudplatform.decision.strategy;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.domain.Reason;
import com.fraudplatform.decision.domain.ReasonCode;
import com.fraudplatform.decision.domain.ReasonSource;
import com.fraudplatform.decision.features.GraphRisk;
import com.fraudplatform.decision.strategy.CompiledStrategy.ChannelPolicy;
import com.fraudplatform.decision.strategy.CompiledStrategy.Rule;
import com.fraudplatform.decision.strategy.CompiledStrategy.Thresholds;
import com.fraudplatform.decision.strategy.CompiledStrategy.Weights;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hybrid decisioning: rules + model + graph + anomaly combined by noisy-OR, then per-segment/channel
 * thresholds, then rule-mandated minimum decisions, then (if the model is unavailable) the channel
 * fail policy. Pure and deterministic — identical semantics to {@code fraudlab/strategy.py}.
 */
public class StrategyEngine {

    /** Below this contribution the model is not listed as a reason (it is always in the signals block). */
    static final double MODEL_REASON_MIN = 0.10;

    public record Signals(Double modelProbability, Double anomalyPercentile, GraphRisk graph, double amount,
                          Channel channel, String segment, Double deviceRiskScore, boolean compromisedIp) {
    }

    public record Outcome(Decision decision, double riskScore, Thresholds thresholds, int rulePoints,
                          List<Reason> reasons, List<String> rulesHit, boolean fallbackApplied) {
    }

    public Outcome evaluate(CompiledStrategy s, EvaluationContext ctx, Signals sig) {
        Weights w = s.weights();
        List<Reason> reasons = new ArrayList<>();
        List<String> hits = new ArrayList<>();
        int points = 0;
        Decision minimum = Decision.APPROVE;

        for (Rule rule : s.rules()) {
            if (!rule.matches(ctx, sig.channel())) continue;
            hits.add(rule.id());
            if (rule.action() == CompiledStrategy.Action.SCORE) {
                points += rule.points();
                reasons.add(new Reason(rule.reasonCode(), ReasonSource.RULE, rule.description(),
                        w.rules() * rule.points() / 100.0, rule.id()));
            } else {
                minimum = Decision.max(minimum, rule.minimumDecision());
                reasons.add(new Reason(rule.reasonCode(), rule.emergency() ? ReasonSource.RULE : ReasonSource.POLICY,
                        rule.description(), 1.0, rule.id()));
            }
        }

        double sRules = Math.min(points, 100) / 100.0;
        double sAnomaly = sig.anomalyPercentile() == null ? 0.0
                : clamp((sig.anomalyPercentile() - s.anomalyTailStart()) / (1.0 - s.anomalyTailStart()));
        double g = sig.graph().max();
        boolean modelAvailable = sig.modelProbability() != null;
        double p = modelAvailable ? sig.modelProbability() : 0.0;

        double keep = (1 - w.model() * p) * (1 - w.rules() * sRules) * (1 - w.graph() * g) * (1 - w.anomaly() * sAnomaly);
        double score = clamp(1 - keep);

        if (modelAvailable && w.model() * p >= MODEL_REASON_MIN) {
            reasons.add(new Reason(ReasonCode.HIGH_MODEL_SCORE, ReasonSource.MODEL,
                    "model probability %.3f".formatted(p), w.model() * p, null));
        }
        if (g > 0) {
            String kind = sig.graph().dominantKind();
            reasons.add(new Reason(ReasonCode.GRAPH_RISK, ReasonSource.GRAPH,
                    sig.graph().explanations().getOrDefault(kind, kind + " graph risk %.2f".formatted(g)), w.graph() * g, null));
        }
        if (sAnomaly > 0) {
            reasons.add(new Reason(ReasonCode.ANOMALOUS_PATTERN, ReasonSource.ANOMALY,
                    "anomaly percentile %.4f".formatted(sig.anomalyPercentile()), w.anomaly() * sAnomaly, null));
        }
        if (sig.compromisedIp()) {
            reasons.add(new Reason(ReasonCode.COMPROMISED_IP, ReasonSource.DEVICE_INTELLIGENCE,
                    "IP flagged by device-risk provider", 0.0, null));
        }

        Thresholds t = s.thresholdsFor(sig.segment(), sig.channel());
        Decision decision = score >= t.decline() ? Decision.DECLINE : score >= t.review() ? Decision.REVIEW : Decision.APPROVE;
        decision = Decision.max(decision, minimum);

        boolean fallback = false;
        if (!modelAvailable) {
            fallback = true;
            ChannelPolicy policy = s.policyFor(sig.channel());
            Decision floor = switch (policy.failMode()) {
                case APPROVE -> policy.maxFailOpenAmount() != null && sig.amount() > policy.maxFailOpenAmount()
                        ? Decision.REVIEW : Decision.APPROVE;
                case REVIEW -> Decision.REVIEW;
                case DECLINE -> Decision.DECLINE;
            };
            decision = Decision.max(decision, floor);
            reasons.add(new Reason(ReasonCode.MODEL_UNAVAILABLE_FALLBACK, ReasonSource.FALLBACK,
                    "channel %s fail mode %s".formatted(sig.channel(), policy.failMode()), 0.0, null));
        }

        reasons.sort(Comparator.comparingDouble(Reason::contribution).reversed());
        return new Outcome(decision, score, t, points, List.copyOf(reasons), List.copyOf(hits), fallback);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
