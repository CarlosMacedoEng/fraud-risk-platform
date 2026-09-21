"""Model feature -> reason code mapping (dependency-free, shared by explain.py and the model-service)."""

# Model feature -> customer-facing reason code (shared with the Java service's ReasonCode enum).
FEATURE_REASON = {
    "card_txn_count_10m": "HIGH_TRANSACTION_VELOCITY", "card_txn_count_1h": "HIGH_TRANSACTION_VELOCITY",
    "device_txn_count_1h": "HIGH_TRANSACTION_VELOCITY", "account_txn_count_24h": "HIGH_TRANSACTION_VELOCITY",
    "is_new_device": "NEW_DEVICE", "has_device": "NEW_DEVICE",
    "ip_country_mismatch": "UNUSUAL_LOCATION", "is_foreign_merchant": "UNUSUAL_LOCATION",
    "card_country_change_1h": "UNUSUAL_LOCATION",
    "is_new_beneficiary": "NEW_BENEFICIARY", "beneficiary_foreign": "NEW_BENEFICIARY",
    "amount_to_baseline": "CUSTOMER_BEHAVIOR_DEVIATION", "account_amount_24h_to_baseline": "CUSTOMER_BEHAVIOR_DEVIATION",
    "amount_log": "CUSTOMER_BEHAVIOR_DEVIATION", "log_seconds_since_last": "CUSTOMER_BEHAVIOR_DEVIATION",
    "mcc_high_risk": "HIGH_RISK_MERCHANT", "hour_of_day": "UNUSUAL_TIME", "is_night": "UNUSUAL_TIME",
    "tenure_log": "CUSTOMER_PROFILE", "risk_tier_elevated": "CUSTOMER_PROFILE",
    "is_transfer": "TRANSACTION_CONTEXT", "is_ecom": "TRANSACTION_CONTEXT", "is_pos": "TRANSACTION_CONTEXT",
    "graph_device_risk": "GRAPH_RISK", "graph_beneficiary_risk": "GRAPH_RISK",
    "graph_merchant_risk": "GRAPH_RISK", "graph_account_risk": "GRAPH_RISK",
}
