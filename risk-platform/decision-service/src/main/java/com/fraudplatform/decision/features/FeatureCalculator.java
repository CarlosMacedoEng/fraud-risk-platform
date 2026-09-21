package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.CustomerProfile;
import com.fraudplatform.decision.domain.Transaction;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Pure, stateless implementation of feature spec fs-1.0 — the Java twin of
 * {@code ml-workbench/src/fraudlab/features.py}. Verified by {@code FeatureCalculatorParityTest}
 * against a golden stream exported by the workbench.
 *
 * <p>Window semantics: an earlier event counts when {@code ts > t - window}.
 */
public final class FeatureCalculator {

    public static final long MIN_10 = 10 * 60 * 1000L;
    public static final long HOUR = 60 * 60 * 1000L;
    public static final long DAY = 24 * HOUR;
    public static final double SINCE_LAST_CAP_SECONDS = 30 * 24 * 3600;

    /** Platform-level MCC risk band used by the model (customer lists are applied by rules, not here). */
    public static final Set<String> HIGH_RISK_MCC = Set.of("5732", "5944", "5816", "6051", "7995");

    public FeatureVector compute(Transaction tx, CustomerProfile profile, EntityState state, GraphRisk graph) {
        long t = tx.eventTime().toEpochMilli();
        double amount = tx.amount().doubleValue();
        String home = profile.homeCountry();
        double baseline = Math.max(profile.avgAmount90d(), 1.0);
        long hour = Math.floorMod(Math.floorDiv(t, HOUR), 24L);

        int card10m = countAfter(state.cardEventTimes(), t - MIN_10);
        int card1h = countAfter(state.cardEventTimes(), t - HOUR);
        int acc24h = 0;
        double accSum24h = 0;
        long[] accTimes = state.accountEventTimes();
        for (int i = 0; i < accTimes.length; i++) {
            if (accTimes[i] > t - DAY) {
                acc24h++;
                accSum24h += state.accountAmounts()[i];
            }
        }
        int dev1h = tx.deviceId() == null ? 0 : countAfter(state.deviceEventTimes(), t - HOUR);

        double since = state.lastTxnTs() == null
                ? SINCE_LAST_CAP_SECONDS
                : Math.min(SINCE_LAST_CAP_SECONDS, (t - state.lastTxnTs()) / 1000.0);

        int countryChange = 0;
        if (tx.cardToken() != null && tx.merchantCountry() != null && state.lastCardTs() != null
                && t - state.lastCardTs() <= HOUR && !tx.merchantCountry().equals(state.lastCardCountry())) {
            countryChange = 1;
        }

        boolean hasDevice = tx.deviceId() != null;
        boolean deviceKnown = state.deviceSeen() || (hasDevice && profile.boundDeviceIds().contains(tx.deviceId()));
        boolean transfer = tx.isTransfer();

        Map<String, Double> f = new LinkedHashMap<>();
        f.put("amount_log", Math.log1p(amount));
        f.put("amount_to_baseline", amount / baseline);
        f.put("hour_of_day", (double) hour);
        f.put("is_night", flag(hour < 6));
        f.put("is_transfer", flag(transfer));
        f.put("is_ecom", flag(tx.channel() == Channel.ECOM));
        f.put("is_pos", flag(tx.channel() == Channel.POS));
        f.put("mcc_high_risk", flag(tx.mcc() != null && HIGH_RISK_MCC.contains(tx.mcc())));
        f.put("is_foreign_merchant", flag(tx.merchantCountry() != null && !tx.merchantCountry().equals(home)));
        f.put("ip_country_mismatch", flag(tx.ipCountry() != null && !tx.ipCountry().equals(home)));
        f.put("has_device", flag(hasDevice));
        f.put("is_new_device", flag(hasDevice && !deviceKnown));
        f.put("is_new_beneficiary", flag(transfer && !state.beneficiarySeen()));
        f.put("beneficiary_foreign", flag(transfer && tx.beneficiaryCountry() != null && !tx.beneficiaryCountry().equals(home)));
        f.put("card_txn_count_10m", (double) card10m);
        f.put("card_txn_count_1h", (double) card1h);
        f.put("account_txn_count_24h", (double) acc24h);
        f.put("account_amount_24h_to_baseline", accSum24h / baseline);
        f.put("device_txn_count_1h", (double) dev1h);
        f.put("log_seconds_since_last", Math.log1p(since));
        f.put("card_country_change_1h", (double) countryChange);
        f.put("tenure_log", Math.log1p(Math.max(profile.tenureDays(), 0)));
        f.put("risk_tier_elevated", flag("elevated".equals(profile.riskTier())));

        f.put("graph_device_risk", graph.device());
        f.put("graph_beneficiary_risk", graph.beneficiary());
        f.put("graph_merchant_risk", graph.merchant());
        f.put("graph_account_risk", graph.account());
        return new FeatureVector(f);
    }

    private static int countAfter(long[] times, long threshold) {
        int n = 0;
        for (long ts : times) {
            if (ts > threshold) n++;
        }
        return n;
    }

    private static double flag(boolean b) {
        return b ? 1.0 : 0.0;
    }
}
