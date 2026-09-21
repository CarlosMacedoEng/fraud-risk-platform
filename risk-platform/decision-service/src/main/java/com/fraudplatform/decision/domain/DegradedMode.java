package com.fraudplatform.decision.domain;

/** Why a decision was made with less information than normal. Always returned to the caller and logged. */
public enum DegradedMode {
    MODEL_UNAVAILABLE,
    FEATURE_STORE_DEGRADED,
    GRAPH_FEATURES_UNAVAILABLE,
    PROFILE_UNAVAILABLE,
    DEVICE_RISK_UNAVAILABLE,
    DEADLINE_EXCEEDED
}
