package com.fraudplatform.decision.domain;

import java.util.Set;

public record CustomerProfile(
        String customerId,
        String segment,
        String homeCountry,
        int tenureDays,
        double avgAmount90d,
        String riskTier,
        Set<String> boundDeviceIds,
        boolean fromFallback) {

    /** Conservative default when the customer is unknown and the profile service is unavailable. */
    public static CustomerProfile unknown(String customerId, String homeCountry) {
        return new CustomerProfile(customerId, "unknown", homeCountry, 0, 50.0, "elevated", Set.of(), true);
    }
}
