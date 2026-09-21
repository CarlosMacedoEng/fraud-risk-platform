package com.fraudplatform.decision.features;

import java.util.Map;

/** Precomputed graph risk per entity kind (device, beneficiary, merchant, account) and its explanation. */
public record GraphRisk(double device, double beneficiary, double merchant, double account,
                        Map<String, String> explanations, boolean available) {

    public static GraphRisk none(boolean available) {
        return new GraphRisk(0, 0, 0, 0, Map.of(), available);
    }

    public double max() {
        return Math.max(Math.max(device, beneficiary), Math.max(merchant, account));
    }

    /** Kind with the highest risk, used for the GRAPH_RISK reason detail. */
    public String dominantKind() {
        double m = max();
        if (m <= 0) return null;
        if (device == m) return "device";
        if (beneficiary == m) return "beneficiary";
        if (merchant == m) return "merchant";
        return "account";
    }
}
