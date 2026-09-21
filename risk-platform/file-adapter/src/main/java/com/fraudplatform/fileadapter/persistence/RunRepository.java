package com.fraudplatform.fileadapter.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Tables of the "ingestion" schema (owned by file-adapter). */
@Repository
public class RunRepository {

    public record Run(UUID runId, String tenantId, String fileType, String fileName, LocalDate businessDate, Integer sequence,
                      String sha256, String status, String rejectionReason, int recordsTotal, int recordsValid,
                      int recordsQuarantined, int recordsDuplicate, int eventsPublished, Instant startedAt,
                      Instant finishedAt, Integer processingMs, String report) {
    }

    public record Quarantined(int lineNumber, String rawRecord, String errors) {
    }

    /** Explicit column list: "SELECT *, report::text AS report" would return two "report" columns. */
    private static final String RUN_COLUMNS = "run_id, tenant_id, file_type, file_name, business_date, sequence, sha256, status, rejection_reason, records_total, records_valid, records_quarantined, records_duplicate, events_published, started_at, finished_at, processing_ms, report::text AS report";

    private final JdbcClient jdbc;

    public RunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claim a file for processing. The partial unique index on sha256 makes this atomic across instances:
     * returns false if the same content is already processing or processed.
     */
    public boolean claim(UUID runId, String tenant, String fileType, String fileName, LocalDate date, Integer seq, String sha) {
        return jdbc.sql("""
                        INSERT INTO ingestion.file_runs (run_id, tenant_id, file_type, file_name, business_date, sequence, sha256, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'PROCESSING') ON CONFLICT DO NOTHING
                        """)
                .params(runId, tenant, fileType, fileName, date == null ? null : Date.valueOf(date), seq, sha).update() == 1;
    }

    public void rejectedWithoutClaim(UUID runId, String fileName, String sha, String reason, String tenant, String fileType,
                                     LocalDate date, Integer seq, String reportJson) {
        jdbc.sql("""
                        INSERT INTO ingestion.file_runs (run_id, tenant_id, file_type, file_name, business_date, sequence, sha256,
                                                         status, rejection_reason, finished_at, processing_ms, report)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'REJECTED', ?, now(), 0, ?::jsonb)
                        """)
                .params(runId, tenant, fileType, fileName, date == null ? null : Date.valueOf(date), seq, sha, reason, reportJson).update();
    }

    public Optional<Run> findActiveBySha(String sha) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM ingestion.file_runs"
                        + " WHERE sha256 = ? AND status IN ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS')")
                .param(sha).query(Run.class).optional();
    }

    public boolean identityAlreadyCompleted(String tenant, String fileType, LocalDate date, int seq) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM ingestion.file_runs WHERE tenant_id = ? AND file_type = ? AND business_date = ?
                                       AND sequence = ? AND status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS'))
                        """)
                .params(tenant, fileType, Date.valueOf(date), seq).query(Boolean.class).single());
    }

    /** A crashed instance leaves PROCESSING rows behind; after the stale timeout another instance takes over. */
    public boolean takeOverStale(UUID runId, int staleMinutes) {
        return jdbc.sql("""
                        UPDATE ingestion.file_runs SET started_at = now()
                        WHERE run_id = ? AND status = 'PROCESSING' AND started_at < now() - make_interval(mins => ?)
                        """)
                .params(runId, staleMinutes).update() == 1;
    }

    public void finish(UUID runId, String status, String reason, int total, int valid, int quarantined, int duplicate,
                       int published, long processingMs, String reportJson) {
        jdbc.sql("""
                        UPDATE ingestion.file_runs SET status = ?, rejection_reason = ?, records_total = ?, records_valid = ?,
                               records_quarantined = ?, records_duplicate = ?, events_published = ?, finished_at = now(),
                               processing_ms = ?, report = ?::jsonb
                        WHERE run_id = ?
                        """)
                .params(status, reason, total, valid, quarantined, duplicate, published, (int) processingMs, reportJson, runId)
                .update();
    }

    public void quarantine(UUID runId, int line, String raw, String errorsJson) {
        jdbc.sql("""
                        INSERT INTO ingestion.quarantined_records (run_id, line_number, raw_record, errors)
                        VALUES (?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING
                        """)
                .params(runId, line, raw.length() > 4000 ? raw.substring(0, 4000) : raw, errorsJson).update();
    }

    public Set<String> alreadyIngested(String tenant, String fileType, Collection<String> keys) {
        if (keys.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.sql("""
                        SELECT natural_key FROM ingestion.ingested_keys WHERE tenant_id = ? AND file_type = ? AND natural_key = ANY(?)
                        """)
                .params(tenant, fileType, keys.toArray(String[]::new)).query(String.class).list());
    }

    public void markIngested(String tenant, String fileType, Collection<String> keys, UUID runId) {
        if (keys.isEmpty()) return;
        jdbc.sql("""
                        INSERT INTO ingestion.ingested_keys (tenant_id, file_type, natural_key, run_id)
                        SELECT ?, ?, k, ? FROM unnest(?::text[]) AS k ON CONFLICT DO NOTHING
                        """)
                .params(tenant, fileType, runId, keys.toArray(String[]::new)).update();
    }

    public List<Run> recent(String tenant, int limit) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM ingestion.file_runs WHERE (?::text IS NULL OR tenant_id = ?)"
                        + " ORDER BY started_at DESC LIMIT ?")
                .params(tenant, tenant, limit).query(Run.class).list();
    }

    public Optional<Run> find(UUID runId) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM ingestion.file_runs WHERE run_id = ?").param(runId)
                .query(Run.class).optional();
    }

    public List<Quarantined> quarantined(UUID runId, int limit) {
        return jdbc.sql("""
                        SELECT line_number, raw_record, errors::text AS errors FROM ingestion.quarantined_records
                        WHERE run_id = ? ORDER BY line_number LIMIT ?
                        """)
                .params(runId, limit).query(Quarantined.class).list();
    }
}
