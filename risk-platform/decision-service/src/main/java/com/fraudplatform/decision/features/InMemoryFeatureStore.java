package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Single-JVM feature store. Used by tests (including the Python parity test) and as the executable
 * reference for the Redis implementation's semantics. Not suitable for multi-instance deployments.
 */
public class InMemoryFeatureStore implements FeatureStore {

    private record TimedAmount(long ts, double amount) {
    }

    private record LastCard(long ts, String country) {
    }

    private final Map<String, Deque<Long>> cardEvents = new HashMap<>();
    private final Map<String, Deque<TimedAmount>> accountEvents = new HashMap<>();
    private final Map<String, Deque<Long>> deviceEvents = new HashMap<>();
    private final Map<String, Long> lastTxn = new HashMap<>();
    private final Map<String, LastCard> lastCard = new HashMap<>();
    private final Map<String, Set<String>> seenDevices = new HashMap<>();
    private final Map<String, Set<String>> seenBeneficiaries = new HashMap<>();

    private static String k(Transaction tx, String id) {
        return tx.tenantId() + ":" + id;
    }

    @Override
    public synchronized EntityState load(Transaction tx) {
        long t = tx.eventTime().toEpochMilli();
        long[] card = tx.cardToken() == null ? new long[0]
                : times(cardEvents.get(k(tx, tx.cardToken())), t - FeatureCalculator.HOUR);
        Deque<TimedAmount> acc = accountEvents.getOrDefault(k(tx, tx.customerId()), new ArrayDeque<>());
        long[] accTimes = acc.stream().filter(e -> e.ts() > t - FeatureCalculator.DAY).mapToLong(TimedAmount::ts).toArray();
        double[] accAmounts = acc.stream().filter(e -> e.ts() > t - FeatureCalculator.DAY).mapToDouble(TimedAmount::amount).toArray();
        long[] dev = tx.deviceId() == null ? new long[0]
                : times(deviceEvents.get(k(tx, tx.deviceId())), t - FeatureCalculator.HOUR);
        LastCard lc = tx.cardToken() == null ? null : lastCard.get(k(tx, tx.cardToken()));
        boolean deviceSeen = tx.deviceId() != null
                && seenDevices.getOrDefault(k(tx, tx.customerId()), Set.of()).contains(tx.deviceId());
        boolean benSeen = tx.beneficiaryId() != null
                && seenBeneficiaries.getOrDefault(k(tx, tx.customerId()), Set.of()).contains(tx.beneficiaryId());
        return new EntityState(card, accTimes, accAmounts, dev, lastTxn.get(k(tx, tx.customerId())),
                lc == null ? null : lc.ts(), lc == null ? null : lc.country(), deviceSeen, benSeen);
    }

    private static long[] times(Deque<Long> q, long threshold) {
        if (q == null) return new long[0];
        return q.stream().mapToLong(Long::longValue).filter(ts -> ts > threshold).toArray();
    }

    @Override
    public synchronized void record(Transaction tx) {
        long t = tx.eventTime().toEpochMilli();
        if (tx.cardToken() != null) {
            Deque<Long> q = cardEvents.computeIfAbsent(k(tx, tx.cardToken()), x -> new ArrayDeque<>());
            q.addLast(t);
            while (!q.isEmpty() && q.peekFirst() <= t - FeatureCalculator.HOUR) q.pollFirst();
            if (tx.merchantCountry() != null) {
                lastCard.put(k(tx, tx.cardToken()), new LastCard(t, tx.merchantCountry()));
            }
        }
        Deque<TimedAmount> acc = accountEvents.computeIfAbsent(k(tx, tx.customerId()), x -> new ArrayDeque<>());
        acc.addLast(new TimedAmount(t, tx.amount().doubleValue()));
        while (!acc.isEmpty() && acc.peekFirst().ts() <= t - FeatureCalculator.DAY) acc.pollFirst();
        if (tx.deviceId() != null) {
            Deque<Long> q = deviceEvents.computeIfAbsent(k(tx, tx.deviceId()), x -> new ArrayDeque<>());
            q.addLast(t);
            while (!q.isEmpty() && q.peekFirst() <= t - FeatureCalculator.HOUR) q.pollFirst();
            seenDevices.computeIfAbsent(k(tx, tx.customerId()), x -> new HashSet<>()).add(tx.deviceId());
        }
        if (tx.isTransfer() && tx.beneficiaryId() != null) {
            seenBeneficiaries.computeIfAbsent(k(tx, tx.customerId()), x -> new HashSet<>()).add(tx.beneficiaryId());
        }
        lastTxn.put(k(tx, tx.customerId()), t);
    }
}
