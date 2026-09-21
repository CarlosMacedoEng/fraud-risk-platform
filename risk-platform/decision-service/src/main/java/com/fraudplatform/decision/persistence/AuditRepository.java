package com.fraudplatform.decision.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** Append-only audit trail for configuration, model and case changes. */
@Repository
public class AuditRepository {

    public record AuditEvent(long auditId, String tenantId, String actor, String action, String entityType,
                             String entityId, String beforeState, String afterState, String correlationId, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String tenant, String actor, String action, String entityType, String entityId,
                       String beforeJson, String afterJson, String correlationId) {
        jdbc.sql("""
                        INSERT INTO audit_events (tenant_id, actor, action, entity_type, entity_id, before_state, after_state, correlation_id)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                        """)
                .params(tenant, actor, action, entityType, entityId, beforeJson, afterJson, correlationId).update();
    }

    public List<AuditEvent> list(String tenant, String entityType, int limit) {
        return jdbc.sql("""
                        SELECT audit_id, tenant_id, actor, action, entity_type, entity_id, before_state::text, after_state::text,
                               correlation_id, created_at
                        FROM audit_events WHERE tenant_id = ? AND (? IS NULL OR entity_type = ?)
                        ORDER BY created_at DESC, audit_id DESC LIMIT ?
                        """)
                .params(tenant, entityType, entityType, limit)
                .query((rs, n) -> new AuditEvent(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getTimestamp(10).toInstant()))
                .list();
    }
}
