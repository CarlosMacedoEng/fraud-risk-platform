package com.fraudplatform.decision.domain;

/**
 * One contributing reason. {@code contribution} is the term's weight in the combined score
 * (w_i * s_i in the noisy-OR), used to rank reasons.
 */
public record Reason(ReasonCode code, ReasonSource source, String detail, double contribution, String ruleId) {

    public String description() {
        return code.description();
    }
}
