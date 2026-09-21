package com.fraudplatform.decision.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** The outcome of scoring one transaction, as persisted and returned. */
public record RiskDecision(
        UUID decisionId,
        String tenantId,
        String transactionId,
        Decision decision,
        double riskScore,
        RiskLevel riskLevel,
        Double modelProbability,
        Double anomalyPercentile,
        double graphRisk,
        int rulePoints,
        List<Reason> reasons,
        Map<String, Double> featureVector,
        String modelVersion,
        String strategyVersion,
        String featureSpecVersion,
        String challengerModelVersion,
        Double challengerProbability,
        Set<DegradedMode> degradedModes,
        double processingMs,
        String clientId,
        String correlationId,
        Instant createdAt) {
}
