package com.fraudplatform.decision.domain;

/** Customer-facing reason codes. Stable contract: add new codes, never rename or reuse. */
public enum ReasonCode {
    HIGH_TRANSACTION_VELOCITY("Unusually many transactions in a short period"),
    NEW_DEVICE("Transaction from a device not previously used by this customer"),
    UNUSUAL_LOCATION("Location inconsistent with the customer's usual or recent location"),
    GRAPH_RISK("Linked to devices, accounts, beneficiaries or merchants associated with fraud"),
    HIGH_MODEL_SCORE("Machine-learning model indicates elevated fraud probability"),
    NEW_BENEFICIARY("First payment to this beneficiary"),
    COMPROMISED_IP("IP address flagged by device/network intelligence"),
    CUSTOMER_BEHAVIOR_DEVIATION("Amount or activity far outside the customer's normal behaviour"),
    HIGH_RISK_MERCHANT("Merchant category with elevated fraud exposure"),
    UNUSUAL_TIME("Activity at an unusual time for this customer"),
    ANOMALOUS_PATTERN("Transaction is a statistical outlier compared with normal traffic"),
    BLOCKED_ENTITY("Device, card, beneficiary or country on a block list"),
    EMERGENCY_RULE("Emergency rule activated by fraud operations"),
    MODEL_UNAVAILABLE_FALLBACK("Model unavailable; decision made by fallback policy"),
    DEGRADED_SIGNALS("Some risk signals were unavailable for this decision");

    private final String description;

    ReasonCode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
