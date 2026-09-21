package com.fraudplatform.decision.messaging;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    public record OutboxRow(UUID eventId, String tenantId, String eventType, String topic, String partitionKey,
                            String payload, String headers, Instant createdAt, int attempts) {
    }

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID eventId, String tenant, String aggregateType, String aggregateId, String eventType, int version,
                       String topic, String partitionKey, String envelopeJson, String headersJson) {
        jdbc.sql("""
                        INSERT INTO outbox_events (event_id, tenant_id, aggregate_type, aggregate_id, event_type, event_version,
                                                   topic, partition_key, payload, headers)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        """)
                .params(eventId, tenant, aggregateType, aggregateId, eventType, version, topic, partitionKey, envelopeJson, headersJson)
                .update();
    }

    /**
     * Claim a batch of unpublished events, oldest first. {@code FOR UPDATE SKIP LOCKED} lets several relay
     * instances run concurrently without publishing the same row twice or blocking each other.
     * Must be called inside a transaction; the locks are held until it commits.
     */
    public List<OutboxRow> lockBatch(int limit) {
        return jdbc.sql("""
                        SELECT event_id, tenant_id, event_type, topic, partition_key, payload::text AS payload,
                               headers::text AS headers, created_at, attempts
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at, event_id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """)
                .param(limit).query(OutboxRow.class).list();
    }

    public void markPublished(List<UUID> ids) {
        if (ids.isEmpty()) return;
        jdbc.sql("UPDATE outbox_events SET published_at = now(), attempts = attempts + 1 WHERE event_id = ANY(?)")
                .param(ids.toArray(UUID[]::new)).update();
    }

    public void markFailed(UUID id, String error) {
        jdbc.sql("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE event_id = ?")
                .params(error == null ? null : error.substring(0, Math.min(error.length(), 500)), id).update();
    }

    public record Backlog(long count, Instant oldest) {
    }

    public Backlog backlog() {
        return jdbc.sql("SELECT count(*), min(created_at) FROM outbox_events WHERE published_at IS NULL")
                .query((rs, n) -> new Backlog(rs.getLong(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant()))
                .single();
    }

    /** Replay: mark already-published events in a window as unpublished so the relay sends them again. */
    public int requeue(String tenant, String eventType, Instant from, Instant to) {
        return jdbc.sql("""
                        UPDATE outbox_events SET published_at = NULL, last_error = 'requeued for replay'
                        WHERE tenant_id = ? AND (?::text IS NULL OR event_type = ?) AND created_at >= ? AND created_at < ?
                          AND published_at IS NOT NULL
                        """)
                .params(tenant, eventType, eventType, Timestamp.from(from), Timestamp.from(to)).update();
    }

    /** Housekeeping: published rows are kept for replay for a retention period (default 14 days). */
    public int purgePublishedOlderThanDays(int days) {
        return jdbc.sql("DELETE FROM outbox_events WHERE published_at IS NOT NULL AND published_at < now() - make_interval(days => ?)")
                .param(days).update();
    }
}
