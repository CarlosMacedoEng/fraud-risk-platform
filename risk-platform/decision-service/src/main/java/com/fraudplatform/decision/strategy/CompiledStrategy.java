package com.fraudplatform.decision.strategy;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.domain.ReasonCode;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** A validated, compiled, immutable strategy version. Compiled once, evaluated per transaction. */
public record CompiledStrategy(
        String tenantId,
        String version,
        String modelVersion,
        String challengerModelVersion,
        String challengerMode,
        Weights weights,
        double anomalyTailStart,
        Thresholds defaultThresholds,
        Map<String, Thresholds> segmentThresholds,
        Map<Channel, Thresholds> channelThresholds,
        Map<Channel, ChannelPolicy> channelPolicies,
        Map<String, Set<String>> lists,
        List<Rule> rules,
        String checksum,
        JsonNode definition) {

    public record Weights(double model, double rules, double graph, double anomaly) {
    }

    /** Partial override: null fields inherit from the level below. */
    public record Thresholds(Double review, Double decline) {
        Thresholds over(Thresholds base) {
            return new Thresholds(review != null ? review : base.review, decline != null ? decline : base.decline);
        }
    }

    public enum FailMode { APPROVE, REVIEW, DECLINE }

    public record ChannelPolicy(FailMode failMode, Double maxFailOpenAmount) {
    }

    public enum Action { SCORE, REVIEW, DECLINE }

    public record Rule(String id, String description, Action action, int points, ReasonCode reasonCode,
                       Set<Channel> channels, boolean emergency, Predicate<EvaluationContext> condition) {

        public boolean matches(EvaluationContext ctx, Channel channel) {
            return (channels.isEmpty() || channels.contains(channel)) && condition.test(ctx);
        }

        public Decision minimumDecision() {
            return switch (action) {
                case SCORE -> Decision.APPROVE;
                case REVIEW -> Decision.REVIEW;
                case DECLINE -> Decision.DECLINE;
            };
        }
    }

    /** Effective thresholds: default, overridden by segment, then by channel (same order as the Python evaluator). */
    public Thresholds thresholdsFor(String segment, Channel channel) {
        Thresholds t = defaultThresholds;
        Thresholds seg = segment == null ? null : segmentThresholds.get(segment);
        if (seg != null) t = seg.over(t);
        Thresholds ch = channelThresholds.get(channel);
        if (ch != null) t = ch.over(t);
        return t;
    }

    public ChannelPolicy policyFor(Channel channel) {
        return channelPolicies.getOrDefault(channel, new ChannelPolicy(FailMode.REVIEW, null));
    }
}
