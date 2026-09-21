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
                                 Instant validatedAt, boolean emergency, String derivedFrom) {
    }

    public record Deployment(String tenantId, String environment, String activeVersion, String previousVersion,
                             String candidateVersion, int rolloutPercentage, String deployedBy, Instant deployedAt,
                             int rowVersion) {
    }

    private final JdbcClient jdbc;

    public StrategyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ versions

    public void insertVersion(String tenant, String version, String definition, String checksum, String status,
                              String summary, String createdBy) {
        insertVersion(tenant, version, definition, checksum, status, summary, createdBy, false, null);
    }

    public void insertVersion(String tenant, String version, String definition, String checksum, String status,
                              String summary, String createdBy, boolean emergency, String derivedFrom) {
        jdbc.sql("""
                        INSERT INTO strategy_versions (tenant_id, version, definition, checksum, status, change_summary,
                                                       created_by, emergency, derived_from)
                        VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                        """)
                .params(tenant, version, definition, checksum, status, summary, createdBy, emergency, derivedFrom).update();
    }

    public Optional<StoredStrategy> findVersion(String tenant, String version) {
        return jdbc.sql("SELECT *, definition::text AS def FROM strategy_versions WHERE tenant_id = ? AND version = ?")
                .params(tenant, version).query(this::mapVersion).optional();
    }

    public List<StoredStrategy> listVersions(String tenant) {
        return jdbc.sql("SELECT *, definition::text AS def FROM strategy_versions WHERE tenant_id = ? ORDER BY created_at DESC, version DESC")
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

    // ------------------------------------------------------------------ deployments

    public Optional<Deployment> findDeployment(String tenant, String environment) {
        return jdbc.sql("SELECT * FROM strategy_deployments WHERE tenant_id = ? AND environment = ?")
                .params(tenant, environment).query(this::mapDeployment).optional();
    }

    public List<Deployment> listDeployments(String tenant) {
        return jdbc.sql("""
                        SELECT * FROM strategy_deployments WHERE tenant_id = ?
                        ORDER BY CASE environment WHEN 'dev' THEN 1 WHEN 'staging' THEN 2 ELSE 3 END
                        """)
                .param(tenant).query(this::mapDeployment).list();
    }

    /** First deployment of an environment. */
    public boolean createDeployment(String tenant, String environment, String version, String actor) {
        return jdbc.sql("""
                        INSERT INTO strategy_deployments (tenant_id, environment, active_version, deployed_by)
                        VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id, environment) DO NOTHING
                        """)
                .params(tenant, environment, version, actor).update() == 1;
    }

    /** Kept for the bootstrap: creates the row when it does not exist. */
    public boolean deploy(String tenant, String environment, String version, String actor, Integer expectedRowVersion) {
        if (expectedRowVersion == null) return createDeployment(tenant, environment, version, actor);
        return activate(tenant, environment, version, actor, expectedRowVersion);
    }

    /**
     * Make {@code version} fully active (100%). Optimistic locking: applies only if the row still has
     * {@code expectedRowVersion}; returns false when someone else changed the deployment in between.
     */
    public boolean activate(String tenant, String environment, String version, String actor, int expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE strategy_deployments
                        SET previous_version = active_version, active_version = ?, candidate_version = NULL,
                            rollout_percentage = 0, deployed_by = ?, deployed_at = now(), row_version = row_version + 1
                        WHERE tenant_id = ? AND environment = ? AND row_version = ?
                        """)
                .params(version, actor, tenant, environment, expectedRowVersion).update() == 1;
    }

    public boolean setCanary(String tenant, String environment, String candidate, int percentage, String actor,
                             int expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE strategy_deployments
                        SET candidate_version = ?, rollout_percentage = ?, deployed_by = ?, deployed_at = now(),
                            row_version = row_version + 1
                        WHERE tenant_id = ? AND environment = ? AND row_version = ?
                        """)
                .params(candidate, percentage, actor, tenant, environment, expectedRowVersion).update() == 1;
    }

    /** Cancel a canary, or (no canary) swap back to the previous version. */
    public boolean rollback(String tenant, String environment, String actor, int expectedRowVersion) {
        return jdbc.sql("""
                        UPDATE strategy_deployments
                        SET active_version   = CASE WHEN candidate_version IS NULL THEN previous_version ELSE active_version END,
                            previous_version = CASE WHEN candidate_version IS NULL THEN active_version ELSE previous_version END,
                            candidate_version = NULL, rollout_percentage = 0,
                            deployed_by = ?, deployed_at = now(), row_version = row_version + 1
                        WHERE tenant_id = ? AND environment = ? AND row_version = ?
                          AND (candidate_version IS NOT NULL OR previous_version IS NOT NULL)
                        """)
                .params(actor, tenant, environment, expectedRowVersion).update() == 1;
    }

    // ------------------------------------------------------------------ mapping

    private StoredStrategy mapVersion(ResultSet rs, int n) throws SQLException {
        Timestamp validated = rs.getTimestamp("validated_at");
        return new StoredStrategy(rs.getString("tenant_id"), rs.getString("version"), rs.getString("def"),
                rs.getString("checksum"), rs.getString("status"), rs.getString("change_summary"),
                rs.getString("created_by"), rs.getTimestamp("created_at").toInstant(), rs.getString("validated_by"),
                validated == null ? null : validated.toInstant(), rs.getBoolean("emergency"), rs.getString("derived_from"));
    }

    private Deployment mapDeployment(ResultSet rs, int n) throws SQLException {
        return new Deployment(rs.getString("tenant_id"), rs.getString("environment"), rs.getString("active_version"),
                rs.getString("previous_version"), rs.getString("candidate_version"), rs.getInt("rollout_percentage"),
                rs.getString("deployed_by"), rs.getTimestamp("deployed_at").toInstant(), rs.getInt("row_version"));
    }
}
