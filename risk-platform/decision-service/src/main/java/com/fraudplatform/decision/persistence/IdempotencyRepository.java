package com.fraudplatform.decision.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency keys are scoped per API client. The first request claims the key with an
 * {@code IN_PROGRESS} row (primary-key insert, so concurrent duplicates cannot both win); the row
 * is completed in the same database transaction that stores the decision.
 */
@Repository
public class IdempotencyRepository {

    public record Entry(String requestHash, String status, UUID decisionId, String responseBody) {
    }

    private final JdbcClient jdbc;

    public IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this call claimed the key. */
    public boolean tryClaim(String clientId, String key, String tenantId, String requestHash) {
        return jdbc.sql("""
                        INSERT INTO idempotency_keys (client_id, idempotency_key, tenant_id, request_hash, status)
                        VALUES (?, ?, ?, ?, 'IN_PROGRESS')
                        ON CONFLICT (client_id, idempotency_key) DO NOTHING
                        """)
                .params(clientId, key, tenantId, requestHash).update() == 1;
    }

    public Optional<Entry> find(String clientId, String key) {
        return jdbc.sql("""
                        SELECT request_hash, status, decision_id, response_body::text AS body
                        FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ?
                        """)
                .params(clientId, key)
                .query((rs, n) -> new Entry(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)))
                .optional();
    }

    public void complete(String clientId, String key, UUID decisionId, String responseBody) {
        jdbc.sql("""
                        UPDATE idempotency_keys SET status = 'COMPLETED', decision_id = ?, response_body = ?::jsonb, completed_at = now()
                        WHERE client_id = ? AND idempotency_key = ?
                        """)
                .params(decisionId, responseBody, clientId, key).update();
    }

    /** Release a claim after a failure so the client can retry with the same key. */
    public void release(String clientId, String key) {
        jdbc.sql("DELETE FROM idempotency_keys WHERE client_id = ? AND idempotency_key = ? AND status = 'IN_PROGRESS'")
                .params(clientId, key).update();
    }

    /** Housekeeping: keys are kept long enough to cover client retry windows (default 7 days). */
    public int purgeOlderThanDays(int days) {
        return jdbc.sql("DELETE FROM idempotency_keys WHERE created_at < now() - make_interval(days => ?)")
                .param(days).update();
    }
}
