package com.fraudplatform.decision.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CaseRepository {

    public record FraudCase(UUID caseId, String tenantId, UUID decisionId, String transactionId, String customerId,
                            String status, String priority, String externalCaseRef, String assignedTo,
                            String resolutionNote, Instant createdAt, Instant updatedAt, int rowVersion) {
    }

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: the unique constraint on decision_id means a duplicate event cannot create a second case. */
    public boolean insertIfAbsent(UUID caseId, String tenant, UUID decisionId, String transactionId, String customerId, String priority) {
        return jdbc.sql("""
                        INSERT INTO fraud_cases (case_id, tenant_id, decision_id, transaction_id, customer_id, priority)
                        VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (decision_id) DO NOTHING
                        """)
                .params(caseId, tenant, decisionId, transactionId, customerId, priority).update() == 1;
    }

    public Optional<FraudCase> findByDecision(UUID decisionId) {
        return jdbc.sql("SELECT * FROM fraud_cases WHERE decision_id = ?").param(decisionId).query(FraudCase.class).optional();
    }

    public Optional<FraudCase> find(String tenant, UUID caseId) {
        return jdbc.sql("SELECT * FROM fraud_cases WHERE tenant_id = ? AND case_id = ?").params(tenant, caseId)
                .query(FraudCase.class).optional();
    }

    public int markOpened(UUID caseId, String externalRef) {
        return jdbc.sql("""
                        UPDATE fraud_cases SET status = 'OPEN', external_case_ref = ?, updated_at = now(), row_version = row_version + 1
                        WHERE case_id = ? AND status = 'PENDING_EXTERNAL'
                        """)
                .params(externalRef, caseId).update();
    }

    public int resolve(String tenant, UUID caseId, String outcome, String note, String actor, int expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE fraud_cases SET status = ?, resolution_note = ?, assigned_to = ?, updated_at = now(),
                               row_version = row_version + 1
                        WHERE tenant_id = ? AND case_id = ? AND row_version = ?
                          AND status IN ('PENDING_EXTERNAL', 'OPEN', 'IN_PROGRESS')
                        """)
                .params(outcome, note, actor, tenant, caseId, expectedRowVersion).update();
    }

    /** Review queue: priority first, then oldest. Served by ix_cases_queue. */
    public List<FraudCase> queue(String tenant, String status, int limit) {
        return jdbc.sql("""
                        SELECT * FROM fraud_cases WHERE tenant_id = ? AND status = ?
                        ORDER BY CASE priority WHEN 'CRITICAL' THEN 1 WHEN 'HIGH' THEN 2 WHEN 'MEDIUM' THEN 3 ELSE 4 END, created_at
                        LIMIT ?
                        """)
                .params(tenant, status, limit).query(FraudCase.class).list();
    }

    public List<FraudCase> pendingExternal(int limit) {
        return jdbc.sql("""
                        SELECT * FROM fraud_cases WHERE status = 'PENDING_EXTERNAL' AND created_at < now() - interval '30 seconds'
                        ORDER BY created_at LIMIT ?
                        """)
                .param(limit).query(FraudCase.class).list();
    }

    public void insertLabel(String tenant, String transactionId, String source, String label, String fraudType, Instant reportedAt) {
        jdbc.sql("""
                        INSERT INTO fraud_labels (tenant_id, transaction_id, source, label, fraud_type, reported_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, transaction_id, source) DO UPDATE
                            SET label = EXCLUDED.label, fraud_type = EXCLUDED.fraud_type, reported_at = EXCLUDED.reported_at,
                                received_at = now()
                        """)
                .params(tenant, transactionId, source, label, fraudType, java.sql.Timestamp.from(reportedAt)).update();
    }
}
