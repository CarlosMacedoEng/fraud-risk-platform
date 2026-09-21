-- V2: versioned strategy configuration, environment deployments, model registry and audit trail.

CREATE TABLE strategy_versions (
    tenant_id       text        NOT NULL REFERENCES tenants (tenant_id),
    version         text        NOT NULL,
    definition      jsonb       NOT NULL,
    checksum        char(64)    NOT NULL,
    status          text        NOT NULL CHECK (status IN ('DRAFT', 'VALIDATED', 'RETIRED')),
    change_summary  text,
    created_by      text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    validated_by    text,
    validated_at    timestamptz,
    PRIMARY KEY (tenant_id, version),
    CONSTRAINT chk_version_format CHECK (version ~ '^[0-9]+\.[0-9]+\.[0-9]+$')
);

-- Which strategy version is active in which environment. One row per (tenant, environment).
-- optimistic locking via row_version prevents two concurrent promotions from silently overwriting.
CREATE TABLE strategy_deployments (
    tenant_id         text        NOT NULL,
    environment       text        NOT NULL CHECK (environment IN ('dev', 'staging', 'prod')),
    active_version    text        NOT NULL,
    previous_version  text,
    deployed_by       text        NOT NULL,
    deployed_at       timestamptz NOT NULL DEFAULT now(),
    row_version       integer     NOT NULL DEFAULT 1,
    PRIMARY KEY (tenant_id, environment),
    FOREIGN KEY (tenant_id, active_version) REFERENCES strategy_versions (tenant_id, version),
    FOREIGN KEY (tenant_id, previous_version) REFERENCES strategy_versions (tenant_id, version)
);

CREATE TABLE model_versions (
    tenant_id        text        NOT NULL REFERENCES tenants (tenant_id),
    model_version    text        NOT NULL,
    feature_spec     text        NOT NULL,
    supervised_sha256 char(64)   NOT NULL,
    anomaly_sha256   char(64)    NOT NULL,
    manifest         jsonb       NOT NULL,
    status           text        NOT NULL CHECK (status IN ('CANDIDATE', 'SHADOW', 'CHAMPION', 'RETIRED')),
    registered_at    timestamptz NOT NULL DEFAULT now(),
    status_changed_at timestamptz NOT NULL DEFAULT now(),
    status_changed_by text       NOT NULL DEFAULT 'system',
    PRIMARY KEY (tenant_id, model_version)
);
-- At most one champion per tenant, enforced by the database.
CREATE UNIQUE INDEX uq_model_champion ON model_versions (tenant_id) WHERE status = 'CHAMPION';

CREATE TABLE audit_events (
    audit_id        bigserial   PRIMARY KEY,
    tenant_id       text        NOT NULL,
    actor           text        NOT NULL,
    action          text        NOT NULL,
    entity_type     text        NOT NULL,
    entity_id       text        NOT NULL,
    before_state    jsonb,
    after_state     jsonb,
    correlation_id  text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_entity ON audit_events (tenant_id, entity_type, entity_id, created_at DESC);
