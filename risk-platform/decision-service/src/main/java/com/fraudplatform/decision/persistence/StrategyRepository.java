package com.fraudplatform.decision.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class StrategyRepository {

    public record StoredStrategy(String tenantId, String version, String definition, String checksum, String status,
                                 String changeSummary, String createdBy, Instant createdAt, String validatedBy,
                                 Instant validatedAt) {
    }

    public record Deployment(String tenantId, String environment, String activeVersion, String previousVersion,
                             String deployedBy, Instant deployedAt, int rowVersion) {
    }

    private final JdbcClient jdbc;

    public StrategyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertVersion(String tenant, String version, String definition, String checksum, String status,
                              String summary, String createdBy) {
        jdbc.sql("""
                        INSERT INTO strategy_versions (tenant_id, version, definition, checksum, status, change_summary, created_by)
                        VALUES (?, ?, ?::jsonb, ?, ?, ?, ?)
                        """)
                .params(tenant, version, definition, checksum, status, summary, createdBy).update();
    }

    public Optional<StoredStrategy> findVersion(String tenant, String version) {
        return jdbc.sql("SELECT *, definition::text AS def FROM strategy_versions WHERE tenant_id = ? AND version = ?")
                .params(tenant, version).query(this::mapVersion).optional();
    }

    public List<StoredStrategy> listVersions(String tenant) {
        return jdbc.sql("SELECT *, definition::text AS def FROM strategy_versions WHERE tenant_id = ? ORDER BY created_at DESC")
                .param(tenant).query(this::mapVersion).list();
    }

    public int updateStatus(String tenant, String version, String fromStatus, String toStatus, String actor) {
        return jdbc.sql("""
                        UPDATE strategy_versions
                        SET status = ?,
                            validated_by = CASE WHEN ? = 'VALIDATED' THEN ? ELSE validated_by END,
                            validated_at = CASE WHEN ? = 'VALIDATED' THEN now() ELSE validated_at END
                        WHERE tenant_id = ? AND version = ? AND status = ?
                        """)
                .params(toStatus, toStatus, actor, toStatus, tenant, version, fromStatus).update();
    }

    public int updateDraftDefinition(String tenant, String version, String definition, String checksum, String summary) {
        return jdbc.sql("""
                        UPDATE strategy_versions SET definition = ?::jsonb, checksum = ?, change_summary = ?
                        WHERE tenant_id = ? AND version = ? AND status = 'DRAFT'
                        """)
                .params(definition, checksum, summary, tenant, version).update();
    }

    public Optional<Deployment> findDeployment(String tenant, String environment) {
        return jdbc.sql("SELECT * FROM strategy_deployments WHERE tenant_id = ? AND environment = ?")
                .params(tenant, environment)
                .query((rs, n) -> new Deployment(rs.getString("tenant_id"), rs.getString("environment"),
                        rs.getString("active_version"), rs.getString("previous_version"), rs.getString("deployed_by"),
                        rs.getTimestamp("deployed_at").toInstant(), rs.getInt("row_version")))
                .optional();
    }

    public List<Deployment> listDeployments(String tenant) {
        return jdbc.sql("SELECT * FROM strategy_deployments WHERE tenant_id = ? ORDER BY environment")
                .param(tenant)
                .query((rs, n) -> new Deployment(rs.getString("tenant_id"), rs.getString("environment"),
                        rs.getString("active_version"), rs.getString("previous_version"), rs.getString("deployed_by"),
                        rs.getTimestamp("deployed_at").toInstant(), rs.getInt("row_version")))
                .list();
    }

    /**
     * Activate {@code version} in {@code environment}. Optimistic locking: the update only applies if the
     * row still has {@code expectedRowVersion}; returns false when someone else deployed in between.
     */
    public boolean deploy(String tenant, String environment, String version, String actor, Integer expectedRowVersion) {
        if (expectedRowVersion == null) {
            return jdbc.sql("""
                            INSERT INTO strategy_deployments (tenant_id, environment, active_version, deployed_by)
                            VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, environment) DO NOTHING
                            """)
                    .params(tenant, environment, version, actor).update() == 1;
        }
        return jdbc.sql("""
                        UPDATE strategy_deployments
                        SET previous_version = active_version, active_version = ?, deployed_by = ?, deployed_at = now(),
                            row_version = row_version + 1
                        WHERE tenant_id = ? AND environment = ? AND row_version = ?
                        """)
                .params(version, actor, tenant, environment, expectedRowVersion).update() == 1;
    }

    private StoredStrategy mapVersion(ResultSet rs, int n) throws SQLException {
        Timestamp validated = rs.getTimestamp("validated_at");
        return new StoredStrategy(rs.getString("tenant_id"), rs.getString("version"), rs.getString("def"),
                rs.getString("checksum"), rs.getString("status"), rs.getString("change_summary"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getString("validated_by"),
                validated == null ? null : validated.toInstant());
    }
}
