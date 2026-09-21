package com.fraudplatform.decision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fraudplatform.decision.domain.Reason;
import com.fraudplatform.decision.domain.RiskDecision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScoreResponse(
        UUID decisionId,
        String transactionId,
        String decision,
        double riskScore,
        String riskLevel,
        Double fraudProbability,
        List<ReasonDto> reasons,
        Signals signals,
        Versions versions,
        List<String> degradedModes,
        double processingTimeMs,
        boolean idempotentReplay,
        String correlationId,
        Instant decidedAt) {

    public record ReasonDto(String code, String source, String description, String detail, double contribution, String ruleId) {
        static ReasonDto of(Reason r) {
            return new ReasonDto(r.code().name(), r.source().name(), r.description(), r.detail(),
                    Math.round(r.contribution() * 10000) / 10000.0, r.ruleId());
        }
    }

    public record Signals(Double modelProbability, Double anomalyPercentile, double graphRisk, int rulePoints) {
    }

    public record Versions(String model, String strategy, String featureSpec, String challengerModel) {
    }

    /** At most {@code maxReasons} are returned; all are stored and visible via GET /v1/decisions/{id}. */
    public static ScoreResponse of(RiskDecision d, boolean replayed, int maxReasons) {
        return new ScoreResponse(d.decisionId(), d.transactionId(), d.decision().name(), round(d.riskScore()),
                d.riskLevel().name(), d.modelProbability() == null ? null : round(d.modelProbability()),
                d.reasons().stream().limit(maxReasons).map(ReasonDto::of).toList(),
                new Signals(d.modelProbability() == null ? null : round(d.modelProbability()),
                        d.anomalyPercentile() == null ? null : round(d.anomalyPercentile()), round(d.graphRisk()), d.rulePoints()),
                new Versions(d.modelVersion(), d.strategyVersion(), d.featureSpecVersion(), d.challengerModelVersion()),
                d.degradedModes().stream().map(Enum::name).sorted().toList(), d.processingMs(), replayed,
                d.correlationId(), d.createdAt());
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000) / 1_000_000.0;
    }
}
