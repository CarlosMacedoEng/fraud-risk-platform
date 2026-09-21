-- V3: percentage (canary) rollout of a candidate strategy version per environment.
-- Backward compatible: new nullable columns with defaults; existing rows keep working unchanged.

ALTER TABLE strategy_deployments
    ADD COLUMN candidate_version   text,
    ADD COLUMN rollout_percentage  integer NOT NULL DEFAULT 0
        CHECK (rollout_percentage BETWEEN 0 AND 100),
    ADD CONSTRAINT fk_candidate_version FOREIGN KEY (tenant_id, candidate_version)
        REFERENCES strategy_versions (tenant_id, version),
    ADD CONSTRAINT chk_candidate_rollout CHECK (
        (candidate_version IS NULL AND rollout_percentage = 0) OR
        (candidate_version IS NOT NULL AND rollout_percentage BETWEEN 1 AND 99));

-- Emergency changes are allowed to skip the promotion order but must say why.
ALTER TABLE strategy_versions
    ADD COLUMN emergency boolean NOT NULL DEFAULT false,
    ADD COLUMN derived_from text;

CREATE INDEX ix_decisions_strategy_version ON risk_decisions (tenant_id, strategy_version, created_at DESC);
