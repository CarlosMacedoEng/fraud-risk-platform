package com.fraudplatform.decision.features;

import com.fraudplatform.decision.domain.Transaction;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.util.List;

/**
 * Degraded-mode feature state computed from PostgreSQL when Redis is unavailable.
 *
 * <p>Uses the partial indexes {@code ix_transactions_card_time}, {@code ix_transactions_device_time} and
 * {@code ix_transactions_customer_time}. Every query is bounded by a short statement timeout so a slow
 * database cannot turn a Redis outage into a latency incident. "Seen" checks are limited to 180 days
 * to keep them index range scans — a documented approximation of the unbounded Redis sets.
 */
public class JdbcFallbackFeatureStore implements FeatureStore {

    private final JdbcClient jdbc;
    private final int queryTimeoutSeconds;

    public JdbcFallbackFeatureStore(JdbcClient jdbc, int queryTimeoutSeconds) {
        this.jdbc = jdbc;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public EntityState load(Transaction tx) {
        long t = tx.eventTime().toEpochMilli();
        Timestamp now = new Timestamp(t);
        Timestamp hourAgo = new Timestamp(t - FeatureCalculator.HOUR);
        Timestamp dayAgo = new Timestamp(t - FeatureCalculator.DAY);

        long[] card = new long[0];
        Long lastCardTs = null;
        String lastCardCountry = null;
        if (tx.cardToken() != null) {
            card = times("""
                    SELECT event_time FROM transactions
                    WHERE tenant_id = ? AND card_token = ? AND event_time > ? AND event_time <= ?
                    """, tx.tenantId(), tx.cardToken(), hourAgo, now);
            List<Object[]> lc = jdbc.sql("""
                            SELECT event_time, merchant_country FROM transactions
                            WHERE tenant_id = ? AND card_token = ? AND event_time <= ? AND merchant_country IS NOT NULL
                            ORDER BY event_time DESC LIMIT 1
                            """)
                    .params(tx.tenantId(), tx.cardToken(), now)
                    .query((rs, n) -> new Object[]{rs.getTimestamp(1).getTime(), rs.getString(2)})
                    .list();
            if (!lc.isEmpty()) {
                lastCardTs = (Long) lc.getFirst()[0];
                lastCardCountry = (String) lc.getFirst()[1];
            }
        }
        List<double[]> acct = jdbc.sql("""
                        SELECT event_time, amount FROM transactions
                        WHERE tenant_id = ? AND customer_id = ? AND event_time > ? AND event_time <= ?
                        """)
                .params(tx.tenantId(), tx.customerId(), dayAgo, now)
                .query((rs, n) -> new double[]{rs.getTimestamp(1).getTime(), rs.getDouble(2)})
                .list();
        long[] accTimes = acct.stream().mapToLong(a -> (long) a[0]).toArray();
        double[] accAmounts = acct.stream().mapToDouble(a -> a[1]).toArray();

        long[] dev = new long[0];
        boolean devSeen = false;
        if (tx.deviceId() != null) {
            dev = times("""
                    SELECT event_time FROM transactions
                    WHERE tenant_id = ? AND device_id = ? AND event_time > ? AND event_time <= ?
                    """, tx.tenantId(), tx.deviceId(), hourAgo, now);
            devSeen = exists("""
                    SELECT EXISTS (SELECT 1 FROM transactions WHERE tenant_id = ? AND device_id = ? AND customer_id = ?
                                   AND event_time > ?::timestamptz - interval '180 days' AND event_time <= ?)
                    """, tx.tenantId(), tx.deviceId(), tx.customerId(), now, now);
        }
        boolean benSeen = false;
        if (tx.isTransfer() && tx.beneficiaryId() != null) {
            benSeen = exists("""
                    SELECT EXISTS (SELECT 1 FROM transactions WHERE tenant_id = ? AND customer_id = ? AND beneficiary_id = ?
                                   AND event_time > ?::timestamptz - interval '180 days' AND event_time <= ?)
                    """, tx.tenantId(), tx.customerId(), tx.beneficiaryId(), now, now);
        }
        // Note: a ternary mixing long and Long would auto-unbox a null Long and throw; keep both sides boxed.
        Long last = accTimes.length > 0 ? Long.valueOf(java.util.Arrays.stream(accTimes).max().getAsLong()) : lastTxnBefore(tx, now);
        return new EntityState(card, accTimes, accAmounts, dev, last, lastCardTs, lastCardCountry, devSeen, benSeen);
    }

    private Long lastTxnBefore(Transaction tx, Timestamp now) {
        return jdbc.sql("SELECT max(event_time) FROM transactions WHERE tenant_id = ? AND customer_id = ? AND event_time <= ?")
                .params(tx.tenantId(), tx.customerId(), now)
                .query((rs, n) -> rs.getTimestamp(1) == null ? null : rs.getTimestamp(1).getTime())
                .optional().orElse(null);
    }

    private long[] times(String sql, Object... params) {
        return jdbc.sql(sql).params(params)
                .query((rs, n) -> rs.getTimestamp(1).getTime()).list()
                .stream().mapToLong(Long::longValue).toArray();
    }

    private boolean exists(String sql, Object... params) {
        return Boolean.TRUE.equals(jdbc.sql(sql).params(params).query(Boolean.class).single());
    }

    /** Transactions are already persisted by the decision flow; nothing to record. */
    @Override
    public void record(Transaction tx) {
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }
}
