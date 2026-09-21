package com.fraudplatform.decision.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Release 2.0 data migration, step 2 of 3 (BACKFILL): fills {@code risk_decisions.channel} for decisions written
 * before V6. Runs online, next to live scoring:
 * <ul>
 *   <li>keyset pagination over {@code ix_decisions_tenant_created} — every batch costs the same, whatever the
 *       table size (no {@code WHERE channel IS NULL LIMIT n} rescans);</li>
 *   <li>one short transaction per batch, so row locks are brief and WAL/replication lag stays bounded;</li>
 *   <li>a pause between batches (throttle) to protect the scoring hot path;</li>
 *   <li>idempotent and restartable: only NULL rows are updated, so a re-run after an interruption is safe.</li>
 * </ul>
 */
@Service
public class DecisionChannelBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DecisionChannelBackfillService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReentrantLock running = new ReentrantLock();   // one backfill per instance at a time

    public DecisionChannelBackfillService(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Reconciliation view used before and after the backfill, and as the gate for the V7 contract step.
     * A full scan with a join: it runs with its own statement timeout, not the 2 s hot-path guard (the rehearsal
     * hit that guard right after the backfill, when the table was full of dead tuples).
     */
    public Map<String, Object> status(String tenant) {
        Map<String, Object> m = tx.execute(s -> {
            jdbc.execute("SET LOCAL statement_timeout = '60s'");
            return jdbc.queryForMap("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE d.channel IS NULL) AS remaining,
                       count(*) FILTER (WHERE d.channel IS NOT NULL AND d.channel <> t.channel) AS mismatched
                FROM risk_decisions d JOIN transactions t USING (tenant_id, transaction_id)
                WHERE d.tenant_id = ?""", tenant);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tenant", tenant);
        out.putAll(m);
        out.put("readyForContract", ((Number) m.get("remaining")).longValue() == 0 && ((Number) m.get("mismatched")).longValue() == 0);
        return out;
    }

    public Map<String, Object> backfill(String tenant, int batchSize, int maxBatches, long pauseMs) {
        if (!running.tryLock()) {
            return Map.of("tenant", tenant, "status", "ALREADY_RUNNING");
        }
        try {
            long start = System.nanoTime();
            long updated = 0;
            int batches = 0;
            Timestamp cursorTs = null;
            UUID cursorId = null;
            while (batches < maxBatches) {
                List<Map<String, Object>> keys = cursorTs == null
                        ? jdbc.queryForList("""
                            SELECT decision_id, created_at FROM risk_decisions WHERE tenant_id = ?
                            ORDER BY created_at DESC, decision_id DESC LIMIT ?""", tenant, batchSize)
                        : jdbc.queryForList("""
                            SELECT decision_id, created_at FROM risk_decisions WHERE tenant_id = ?
                              AND (created_at, decision_id) < (?, ?)
                            ORDER BY created_at DESC, decision_id DESC LIMIT ?""", tenant, cursorTs, cursorId, batchSize);
                if (keys.isEmpty()) break;
                Map<String, Object> last = keys.get(keys.size() - 1);
                cursorTs = (Timestamp) last.get("created_at");
                cursorId = (UUID) last.get("decision_id");
                UUID[] ids = keys.stream().map(k -> (UUID) k.get("decision_id")).toArray(UUID[]::new);
                updated += jdbc.update(con -> {
                    var ps = con.prepareStatement("""
                            UPDATE risk_decisions d SET channel = t.channel
                            FROM transactions t
                            WHERE d.decision_id = ANY (?) AND d.channel IS NULL
                              AND t.tenant_id = d.tenant_id AND t.transaction_id = d.transaction_id""");
                    ps.setArray(1, con.createArrayOf("uuid", ids));
                    return ps;
                });
                batches++;
                if (pauseMs > 0) sleep(pauseMs);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.info("decision-channel backfill tenant={} batches={} updated={} durationMs={}", tenant, batches, updated, ms);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("tenant", tenant);
            out.put("batches", batches);
            out.put("updated", updated);
            out.put("durationMs", ms);
            out.put("complete", batches < maxBatches);
            try {
                out.put("status", status(tenant));
            } catch (RuntimeException e) {       // the work above is committed; do not report it as failed
                log.warn("reconciliation after backfill failed tenant={}: {}", tenant, e.getMessage());
                out.put("status", Map.of("error", "reconciliation unavailable, retry GET .../migrations/decision-channel"));
            }
            return out;
        } finally {
            running.unlock();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("backfill interrupted", e);
        }
    }
}
