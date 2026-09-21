package com.fraudplatform.decision.features;

/**
 * Rolling state read from the feature store <em>before</em> the current transaction is recorded.
 * Arrays contain only events inside the longest window of each key type (1h card/device, 24h account).
 */
public record EntityState(
        long[] cardEventTimes,
        long[] accountEventTimes,
        double[] accountAmounts,
        long[] deviceEventTimes,
        Long lastTxnTs,
        Long lastCardTs,
        String lastCardCountry,
        boolean deviceSeen,
        boolean beneficiarySeen) {

    public static EntityState empty() {
        return new EntityState(new long[0], new long[0], new double[0], new long[0], null, null, null, false, false);
    }
}
