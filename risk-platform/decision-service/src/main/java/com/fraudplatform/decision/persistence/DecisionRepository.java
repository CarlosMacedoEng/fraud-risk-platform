package com.fraudplatform.decision.persistence;

import com.fraudplatform.decision.domain.Decision;
import com.fraudplatform.decision.domain.DegradedMode;
import com.fraudplatform.decision.domain.Reason;
import com.fraudplatform.decision.domain.RiskDecision;
import com.fraudplatform.decision.domain.RiskLevel;
import com.fraudplatform.decision.domain.Transaction;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DecisionRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public DecisionRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** @return false when the transaction already exists (natural-key duplicate). */
    public boolean insertTransaction(Transaction tx, String source) {
        int rows = jdbc.sql("""
                        INSERT INTO transactions (tenant_id, transaction_id, customer_id, account_id, event_time, transaction_type,
                            channel, amount, currency, card_token, merchant_id, mcc, merchant_country, beneficiary_id,
                            beneficiary_country, device_id, ip_address, ip_country, source)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, transaction_id) DO NOTHING
                        """)
                .params(tx.tenantId(), tx.transactionId(), tx.customerId(), tx.accountId(), Timestamp.from(tx.eventTime()),
                        tx.type().name(), tx.channel().name(), tx.amount(), tx.currency(), tx.cardToken(), tx.merchantId(),
                        tx.mcc(), tx.merchantCountry(), tx.beneficiaryId(), tx.beneficiaryCountry(), tx.deviceId(),
                        tx.ipAddress(), tx.ipCountry(), source)
                .update();
        return rows == 1;
    }

    public void insertDecision(RiskDecision d) {
        jdbc.sql("""
                        INSERT INTO risk_decisions (decision_id, tenant_id, transaction_id, decision, risk_score, risk_level,
                            model_probability, anomaly_percentile, graph_risk, rule_points, reasons, feature_vector, model_version,
                            strategy_version, feature_spec_version, challenger_model_version, challenger_probability,
                            degraded_modes, processing_ms, client_id, correlation_id, created_at, channel)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?,
                                (SELECT t.channel FROM transactions t WHERE t.tenant_id = ? AND t.transaction_id = ?))
                        """)
                .params(d.decisionId(), d.tenantId(), d.transactionId(), d.decision().name(), d.riskScore(), d.riskLevel().name(),
                        d.modelProbability(), d.anomalyPercentile(), d.graphRisk(), d.rulePoints(),
                        json.writeValueAsString(d.reasons()), json.writeValueAsString(d.featureVector()), d.modelVersion(),
                        d.strategyVersion(), d.featureSpecVersion(), d.challengerModelVersion(), d.challengerProbability(),
                        "{" + d.degradedModes().stream().map(Enum::name).collect(Collectors.joining(",")) + "}",
                        d.processingMs(), d.clientId(), d.correlationId(), Timestamp.from(d.createdAt()),
                        d.tenantId(), d.transactionId())   // release 2.0: channel (V6); PK lookup, same transaction
                .update();
    }

    public Optional<RiskDecision> findById(String tenantId, UUID decisionId) {
        return jdbc.sql("SELECT * FROM risk_decisions WHERE tenant_id = ? AND decision_id = ?")
                .params(tenantId, decisionId).query(this::map).optional();
    }

    public Optional<RiskDecision> findByTransaction(String tenantId, String transactionId) {
        return jdbc.sql("SELECT * FROM risk_decisions WHERE tenant_id = ? AND transaction_id = ?")
                .params(tenantId, transactionId).query(this::map).optional();
    }

    /**
     * Keyset pagination ordered by (created_at DESC, decision_id DESC); served by
     * {@code ix_decisions_tenant_created}. Offset pagination is avoided because deep pages get slower
     * linearly and rows shift while new decisions arrive.
     */
    public List<RiskDecision> list(String tenantId, Decision decision, Instant beforeCreatedAt, UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM risk_decisions WHERE tenant_id = :tenant");
        if (decision != null) sql.append(" AND decision = :decision");
        if (beforeCreatedAt != null) sql.append(" AND (created_at, decision_id) < (:beforeTs, :beforeId)");
        sql.append(" ORDER BY created_at DESC, decision_id DESC LIMIT :limit");
        JdbcClient.StatementSpec spec = jdbc.sql(sql.toString()).param("tenant", tenantId).param("limit", limit);
        if (decision != null) spec = spec.param("decision", decision.name());
        if (beforeCreatedAt != null) spec = spec.param("beforeTs", Timestamp.from(beforeCreatedAt)).param("beforeId", beforeId);
        return spec.query(this::map).list();
    }

    private RiskDecision map(ResultSet rs, int n) throws SQLException {
        List<Reason> reasons = json.readValue(rs.getString("reasons"), new TypeReference<>() {
        });
        Map<String, Double> features = json.readValue(rs.getString("feature_vector"), new TypeReference<>() {
        });
        Array degraded = rs.getArray("degraded_modes");
        Set<DegradedMode> modes = EnumSet.noneOf(DegradedMode.class);
        if (degraded != null) {
            Arrays.stream((String[]) degraded.getArray()).map(DegradedMode::valueOf).forEach(modes::add);
        }
        return new RiskDecision(
                rs.getObject("decision_id", UUID.class), rs.getString("tenant_id"), rs.getString("transaction_id"),
                Decision.valueOf(rs.getString("decision")), rs.getDouble("risk_score"), RiskLevel.valueOf(rs.getString("risk_level")),
                nullableDouble(rs, "model_probability"), nullableDouble(rs, "anomaly_percentile"), rs.getDouble("graph_risk"),
                rs.getInt("rule_points"), reasons, features, rs.getString("model_version"), rs.getString("strategy_version"),
                rs.getString("feature_spec_version"), rs.getString("challenger_model_version"),
                nullableDouble(rs, "challenger_probability"), modes, rs.getDouble("processing_ms"), rs.getString("client_id"),
                rs.getString("correlation_id"), rs.getTimestamp("created_at").toInstant());
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
