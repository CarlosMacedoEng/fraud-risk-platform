package com.fraudplatform.decision.domain;

public enum RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL;

    public static RiskLevel of(double score, double reviewThreshold, double declineThreshold) {
        if (score >= declineThreshold) return CRITICAL;
        if (score >= reviewThreshold) return HIGH;
        if (score >= reviewThreshold * 0.5) return MEDIUM;
        return LOW;
    }
}
