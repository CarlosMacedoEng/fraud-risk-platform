package com.fraudplatform.decision.persistence;

import com.fraudplatform.decision.domain.Channel;
import com.fraudplatform.decision.domain.Transaction;
import com.fraudplatform.decision.domain.TransactionType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Reads recent decisions with the exact inputs they were made with, for what-if replay. */
@Repository
public class SimulationQueries {

    public record Row(Transaction transaction, String segment, Map<String, Double> features, Double modelProbability,
                      Double anomalyPercentile, String modelVersion, String decision, String strategyVersion,
                      String fraudLabel) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public SimulationQueries(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Most recent N decisions. The LEFT JOIN to customers supplies the segment; the fraud label (if the
     * labels table exists and has an entry) lets the simulation report precision changes too.
     */
    public List<Row> recent(String tenant, int limit) {
        return jdbc.sql("""
                        SELECT t.*, COALESCE(c.segment, 'unknown') AS segment, d.feature_vector::text AS fv,
                               d.model_probability, d.anomaly_percentile, d.model_version, d.decision, d.strategy_version,
                               (SELECT l.label FROM fraud_labels l WHERE l.tenant_id = t.tenant_id AND l.transaction_id = t.transaction_id
                                ORDER BY l.received_at DESC LIMIT 1) AS fraud_label
                        FROM risk_decisions d
                        JOIN transactions t ON t.tenant_id = d.tenant_id AND t.transaction_id = d.transaction_id
                        LEFT JOIN customers c ON c.tenant_id = t.tenant_id AND c.customer_id = t.customer_id
                        WHERE d.tenant_id = ?
                        ORDER BY d.created_at DESC
                        LIMIT ?
                        """)
                .params(tenant, limit)
                .query((rs, n) -> {
                    Transaction tx = new Transaction(rs.getString("tenant_id"), rs.getString("transaction_id"),
                            rs.getString("customer_id"), rs.getString("account_id"), rs.getTimestamp("event_time").toInstant(),
                            TransactionType.valueOf(rs.getString("transaction_type")), Channel.valueOf(rs.getString("channel")),
                            rs.getBigDecimal("amount"), rs.getString("currency"), rs.getString("card_token"),
                            rs.getString("merchant_id"), trim(rs.getString("mcc")), trim(rs.getString("merchant_country")),
                            rs.getString("beneficiary_id"), trim(rs.getString("beneficiary_country")), rs.getString("device_id"),
                            rs.getString("ip_address"), trim(rs.getString("ip_country")));
                    Map<String, Double> features = json.readValue(rs.getString("fv"), new TypeReference<>() {
                    });
                    double p = rs.getDouble("model_probability");
                    Double prob = rs.wasNull() ? null : p;
                    double a = rs.getDouble("anomaly_percentile");
                    Double anomaly = rs.wasNull() ? null : a;
                    return new Row(tx, rs.getString("segment"), features, prob, anomaly, rs.getString("model_version"),
                            rs.getString("decision"), rs.getString("strategy_version"), rs.getString("fraud_label"));
                })
                .list();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
