package com.fraudplatform.decision.domain;

/** Ordered by severity; {@link #max} is used to apply rule-mandated minimum decisions. */
public enum Decision {
    APPROVE, REVIEW, DECLINE;

    public static Decision max(Decision a, Decision b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
