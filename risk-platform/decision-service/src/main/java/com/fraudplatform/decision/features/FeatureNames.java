package com.fraudplatform.decision.features;

import java.util.List;

/** Feature names and order of feature spec {@value #SPEC_VERSION}; must match ml-workbench features.py. */
public final class FeatureNames {

    public static final String SPEC_VERSION = "fs-1.0";

    public static final List<String> BASE = List.of(
            "amount_log", "amount_to_baseline", "hour_of_day", "is_night", "is_transfer", "is_ecom", "is_pos",
            "mcc_high_risk", "is_foreign_merchant", "ip_country_mismatch", "has_device", "is_new_device",
            "is_new_beneficiary", "beneficiary_foreign", "card_txn_count_10m", "card_txn_count_1h",
            "account_txn_count_24h", "account_amount_24h_to_baseline", "device_txn_count_1h",
            "log_seconds_since_last", "card_country_change_1h", "tenure_log", "risk_tier_elevated");

    public static final List<String> GRAPH = List.of(
            "graph_device_risk", "graph_beneficiary_risk", "graph_merchant_risk", "graph_account_risk");

    private FeatureNames() {
    }
}
