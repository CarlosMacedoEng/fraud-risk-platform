package com.fraudplatform.decision.strategy;

import com.fraudplatform.decision.features.FeatureNames;

import java.util.HashMap;
import java.util.Map;

/**
 * Whitelist of fields a customer rule may reference. Anything else is rejected at validation time,
 * which is what makes the DSL safe to expose to risk analysts (ADR-004).
 */
public final class FieldCatalog {

    public enum Type { NUMBER, STRING }

    private static final Map<String, Type> FIELDS = new HashMap<>();

    static {
        FeatureNames.BASE.forEach(f -> FIELDS.put(f, Type.NUMBER));
        FeatureNames.GRAPH.forEach(f -> FIELDS.put(f, Type.NUMBER));
        FIELDS.put("amount", Type.NUMBER);
        FIELDS.put("model_probability", Type.NUMBER);
        FIELDS.put("anomaly_percentile", Type.NUMBER);
        FIELDS.put("graph_risk", Type.NUMBER);
        FIELDS.put("device_risk_score", Type.NUMBER);
        for (String s : new String[]{"channel", "transaction_type", "mcc", "merchant_country", "ip_country",
                "beneficiary_country", "device_id", "card_token", "card_bin", "merchant_id", "beneficiary_id",
                "customer_segment", "customer_id", "currency"}) {
            FIELDS.put(s, Type.STRING);
        }
    }

    private FieldCatalog() {
    }

    public static Type typeOf(String field) {
        return FIELDS.get(field);
    }

    public static Map<String, Type> all() {
        return Map.copyOf(FIELDS);
    }
}
