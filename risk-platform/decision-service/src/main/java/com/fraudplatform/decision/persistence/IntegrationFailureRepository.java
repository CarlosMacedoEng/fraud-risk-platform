package com.fraudplatform.decision.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Durable record of failed asynchronous integration attempts (not hot-path lookups, which are metrics only).
 * Support engineers query it first when a customer asks "why was no case opened for transaction X?".
 */
@Repository
public class IntegrationFailureRepository {

    public record Failure(long failureId, String tenantId, String integration, String operation, String errorCode,
                          String errorMessage, String referenceId, String correlationId, int retryCount,
                          Instant occurredAt, Instant resolvedAt) {
    }

    private final JdbcClient jdbc;

    public IntegrationFailureRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String tenant, String integration, String operation, String errorCode, String message,
                       String referenceId, String correlationId, int retryCount) {
        jdbc.sql("""
                        INSERT INTO integration_failures (tenant_id, integration, operation, error_code, error_message,
                                                          reference_id, correlation_id, retry_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(tenant, integration, operation, errorCode, message == null ? null : message.substring(0, Math.min(1000, message.length())),
                        referenceId, correlationId, retryCount).update();
    }

    public int resolve(String integration, String referenceId) {
        return jdbc.sql("UPDATE integration_failures SET resolved_at = now() WHERE integration = ? AND reference_id = ? AND resolved_at IS NULL")
                .params(integration, referenceId).update();
    }

    public List<Failure> open(String tenant, int limit) {
        return jdbc.sql("""
                        SELECT * FROM integration_failures WHERE tenant_id = ? AND resolved_at IS NULL
                        ORDER BY occurred_at DESC LIMIT ?
                        """)
                .params(tenant, limit).query(Failure.class).list();
    }
}
