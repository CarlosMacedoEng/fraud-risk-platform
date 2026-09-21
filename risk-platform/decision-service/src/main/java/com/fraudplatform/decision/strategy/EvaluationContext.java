package com.fraudplatform.decision.strategy;

import java.util.HashMap;
import java.util.Map;

/** Field values a rule can see: features, raw transaction fields, customer segment and risk signals. */
public final class EvaluationContext {

    private final Map<String, Object> values = new HashMap<>();

    public EvaluationContext put(String field, Object value) {
        if (value != null) values.put(field, value);
        return this;
    }

    public Object get(String field) {
        return values.get(field);
    }

    public Double number(String field) {
        Object v = values.get(field);
        return v instanceof Number n ? n.doubleValue() : null;
    }

    public String string(String field) {
        Object v = values.get(field);
        return v == null ? null : v.toString();
    }

    public Map<String, Object> asMap() {
        return Map.copyOf(values);
    }
}
