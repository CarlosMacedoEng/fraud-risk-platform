package com.fraudplatform.decision.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class ModelRepository {

    public record ModelVersion(String tenantId, String modelVersion, String featureSpec, String status,
                               Instant registeredAt, Instant statusChangedAt, String statusChangedBy) {
    }

    private final JdbcClient jdbc;

    public ModelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean register(String tenant, String version, String featureSpec, String supSha, String anoSha,
                            String manifestJson, String actor) {
        return jdbc.sql("""
                        INSERT INTO model_versions (tenant_id, model_version, feature_spec, supervised_sha256, anomaly_sha256,
                                                    manifest, status, status_changed_by)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, 'CANDIDATE', ?)
                        ON CONFLICT (tenant_id, model_version) DO NOTHING
                        """)
                .params(tenant, version, featureSpec, supSha, anoSha, manifestJson, actor).update() == 1;
    }

    public Optional<ModelVersion> find(String tenant, String version) {
        return jdbc.sql("""
                        SELECT tenant_id, model_version, feature_spec, status, registered_at, status_changed_at, status_changed_by
                        FROM model_versions WHERE tenant_id = ? AND model_version = ?
                        """)
                .params(tenant, version).query(ModelVersion.class).optional();
    }

    public List<ModelVersion> list(String tenant) {
        return jdbc.sql("""
                        SELECT tenant_id, model_version, feature_spec, status, registered_at, status_changed_at, status_changed_by
                        FROM model_versions WHERE tenant_id = ? ORDER BY model_version
                        """)
                .param(tenant).query(ModelVersion.class).list();
    }

    public Optional<String> champion(String tenant) {
        return jdbc.sql("SELECT model_version FROM model_versions WHERE tenant_id = ? AND status = 'CHAMPION'")
                .param(tenant).query(String.class).optional();
    }

    public int setStatus(String tenant, String version, String fromStatus, String toStatus, String actor) {
        return jdbc.sql("""
                        UPDATE model_versions SET status = ?, status_changed_at = now(), status_changed_by = ?
                        WHERE tenant_id = ? AND model_version = ? AND status = ?
                        """)
                .params(toStatus, actor, tenant, version, fromStatus).update();
    }
}
