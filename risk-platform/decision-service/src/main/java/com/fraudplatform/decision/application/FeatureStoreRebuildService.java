package com.fraudplatform.decision.application;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.domain.TransactionType;
import com.fraudplatform.decision.features.RedisFeatureStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rebuilds Redis feature state from the system of record (PostgreSQL) — the recovery procedure after a
 * Redis flush, failover to an empty replica, or an outage during which writes were skipped (ADR-003:
 * Redis holds only reconstructable data). Streams transactions in event-time order and replays them
 * through the same {@code record} logic as real-time scoring.
 */
@Service
public class FeatureStoreRebuildService {

    private static final Logger log = LoggerFactory.getLogger(FeatureStoreRebuildService.class);

    private final JdbcTemplate jdbc;
    private final RedisFeatureStore store;

    public FeatureStoreRebuildService(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.store = new RedisFeatureStore(redis);
    }

    public Map<String, Object> rebuild(String tenant, int days) {
        long start = System.nanoTime();
        AtomicLong n = new AtomicLong();
        Timestamp from = Timestamp.from(Instant.now().minus(Duration.ofDays(days)));
        jdbc.setFetchSize(1000);   // stream, do not load the whole window into memory
        jdbc.query("""
                        SELECT tenant_id, transaction_id, customer_id, account_id, event_time, transaction_type, channel, amount,
                               currency, card_token, merchant_id, mcc, merchant_country, beneficiary_id, beneficiary_country,
                               device_id, ip_address, ip_country
                        FROM transactions WHERE tenant_id = ? AND event_time >= ? ORDER BY event_time
                        """,
                rs -> {
                    store.record(new Transaction(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getTimestamp(5).toInstant(), TransactionType.valueOf(rs.getString(6)),
                            Channel.valueOf(rs.getString(7)), rs.getBigDecimal(8), rs.getString(9), rs.getString(10),
                            rs.getString(11), trim(rs.getString(12)), trim(rs.getString(13)), rs.getString(14),
                            trim(rs.getString(15)), rs.getString(16), rs.getString(17), trim(rs.getString(18))));
                    n.incrementAndGet();
                }, tenant, from);
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.warn("feature store rebuilt tenant={} days={} transactions={} ms={}", tenant, days, n.get(), ms);
        return Map.of("tenant", tenant, "days", days, "transactionsReplayed", n.get(), "durationMs", ms);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
