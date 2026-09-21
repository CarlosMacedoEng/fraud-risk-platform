-- V5: read-only reporting views — the contract offered to other services (file-adapter reconciliation,
-- BI extracts). Consumers read views, never the underlying tables, so decision-service can evolve its
-- schema without breaking them. Production: a dedicated role with SELECT on schema "reporting" only.

CREATE SCHEMA IF NOT EXISTS reporting;

CREATE OR REPLACE VIEW reporting.v_decisions AS
SELECT d.tenant_id,
       d.transaction_id,
       t.customer_id,
       t.transaction_type,
       t.channel,
       t.amount,
       t.currency,
       t.event_time,
       t.source,
       d.decision_id,
       d.decision,
       d.risk_score,
       d.risk_level,
       d.strategy_version,
       d.model_version,
       d.created_at AS decided_at
FROM risk_decisions d
JOIN transactions t ON t.tenant_id = d.tenant_id AND t.transaction_id = d.transaction_id;

CREATE OR REPLACE VIEW reporting.v_labels AS
SELECT tenant_id, transaction_id, source, label, fraud_type, reported_at, received_at
FROM fraud_labels;
