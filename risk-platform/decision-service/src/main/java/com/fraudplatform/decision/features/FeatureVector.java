package com.fraudplatform.decision.features;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Named feature values; models select and order what they need via their manifest. */
public final class FeatureVector {

    private final Map<String, Double> values;

    public FeatureVector(Map<String, Double> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public double get(String name) {
        Double v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("feature not computed: " + name);
        }
        return v;
    }

    public float[] toArray(List<String> names) {
        float[] out = new float[names.size()];
        for (int i = 0; i < names.size(); i++) {
            out[i] = (float) get(names.get(i));
        }
        return out;
    }

    public Map<String, Double> asMap() {
        return values;
    }
}
