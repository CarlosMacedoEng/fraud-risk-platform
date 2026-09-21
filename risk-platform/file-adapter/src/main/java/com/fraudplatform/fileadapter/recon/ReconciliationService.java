package com.fraudplatform.fileadapter.recon;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Daily reconciliation between the core-banking settlement file and the platform's decisions
 * (read through {@code reporting.v_decisions}).
 *
 * <table>
 *   <tr><td>MATCHED</td><td>approved/reviewed, settled, same amount</td></tr>
 *   <tr><td>AMOUNT_MISMATCH</td><td>settled amount differs from the scored amount</td></tr>
 *   <tr><td>SETTLED_BUT_DECLINED</td><td><b>critical</b>: the platform declined, the core settled — the decline was not enforced</td></tr>
 *   <tr><td>NOT_SCORED</td><td><b>critical</b>: settled but never scored — a channel bypasses fraud checks</td></tr>
 *   <tr><td>APPROVED_NOT_SETTLED</td><td>approved on the business date but absent from settlement (timing or lost message)</td></tr>
 *   <tr><td>REVERSED</td><td>informational</td></tr>
 * </table>
 */
@Service
public class ReconciliationService {

    public record Row(String transactionId, String category, String decision, BigDecimal platformAmount,
                      BigDecimal settledAmount, String settlementStatus) {
    }

    public record Settlement(String transactionId, BigDecimal settledAmount, String status) {
    }

    private record PlatformDecision(String decision, BigDecimal amount) {
    }

    private final JdbcClient jdbc;

    public ReconciliationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Integer> reconcile(String tenant, LocalDate date, List<Settlement> settlements, UUID runId, Path outboundDir)
            throws IOException {
        Map<String, PlatformDecision> decisions = new HashMap<>();
        List<String> ids = settlements.stream().map(Settlement::transactionId).toList();
        jdbc.sql("SELECT transaction_id, decision, amount FROM reporting.v_decisions WHERE tenant_id = ? AND transaction_id = ANY(?)")
                .params(tenant, ids.toArray(String[]::new))
                .query(rs -> {
                    decisions.put(rs.getString(1), new PlatformDecision(rs.getString(2), rs.getBigDecimal(3)));
                });

        List<Row> rows = new ArrayList<>();
        for (Settlement s : settlements) {
            PlatformDecision d = decisions.get(s.transactionId());
            String category;
            if (d == null) category = "NOT_SCORED";
            else if ("REVERSED".equals(s.status())) category = "REVERSED";
            else if ("DECLINE".equals(d.decision())) category = "SETTLED_BUT_DECLINED";
            else if (d.amount().compareTo(s.settledAmount()) != 0) category = "AMOUNT_MISMATCH";
            else category = "MATCHED";
            rows.add(new Row(s.transactionId(), category, d == null ? null : d.decision(), d == null ? null : d.amount(),
                    s.settledAmount(), s.status()));
        }
        // Reverse direction: approvals of the business date that never settled.
        jdbc.sql("""
                        SELECT transaction_id, decision, amount FROM reporting.v_decisions
                        WHERE tenant_id = ? AND decision = 'APPROVE' AND source = 'REALTIME'
                          AND event_time >= ?::date AND event_time < ?::date + 1 AND NOT (transaction_id = ANY(?))
                        """)
                .params(tenant, Date.valueOf(date), Date.valueOf(date), ids.toArray(String[]::new))
                .query(rs -> {
                    rows.add(new Row(rs.getString(1), "APPROVED_NOT_SETTLED", rs.getString(2), rs.getBigDecimal(3), null, null));
                });

        for (Row r : rows) {
            jdbc.sql("""
                            INSERT INTO ingestion.reconciliation_records (tenant_id, business_date, transaction_id, category,
                                   platform_decision, platform_amount, settled_amount, settlement_status, run_id)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT (tenant_id, business_date, transaction_id) DO UPDATE SET category = EXCLUDED.category,
                                   platform_decision = EXCLUDED.platform_decision, platform_amount = EXCLUDED.platform_amount,
                                   settled_amount = EXCLUDED.settled_amount, settlement_status = EXCLUDED.settlement_status,
                                   run_id = EXCLUDED.run_id, created_at = now()
                            """)
                    .params(tenant, Date.valueOf(date), r.transactionId(), r.category(), r.decision(), r.platformAmount(),
                            r.settledAmount(), r.settlementStatus(), runId).update();
        }
        writeOutbound(tenant, date, rows, outboundDir);
        Map<String, Integer> summary = new TreeMap<>();
        rows.forEach(r -> summary.merge(r.category(), 1, Integer::sum));
        return summary;
    }

    private static void writeOutbound(String tenant, LocalDate date, List<Row> rows, Path dir) throws IOException {
        Files.createDirectories(dir);
        Path out = dir.resolve("RECON_%s_%s.csv".formatted(tenant, date.toString().replace("-", "")));
        StringBuilder sb = new StringBuilder("transaction_id,category,platform_decision,platform_amount,settled_amount,settlement_status\n");
        for (Row r : rows) {
            sb.append(r.transactionId()).append(',').append(r.category()).append(',').append(nz(r.decision())).append(',')
                    .append(nz(r.platformAmount())).append(',').append(nz(r.settledAmount())).append(',')
                    .append(nz(r.settlementStatus())).append('\n');
        }
        Files.writeString(out, sb.toString());
    }

    private static String nz(Object o) {
        return o == null ? "" : o.toString();
    }

    public List<Map<String, Object>> results(String tenant, LocalDate date, String category) {
        return jdbc.sql("""
                        SELECT transaction_id, category, platform_decision, platform_amount, settled_amount, settlement_status
                        FROM ingestion.reconciliation_records
                        WHERE tenant_id = ? AND business_date = ? AND (?::text IS NULL OR category = ?)
                        ORDER BY category, transaction_id
                        """)
                .params(tenant, Date.valueOf(date), category, category).query().listOfRows();
    }
}
