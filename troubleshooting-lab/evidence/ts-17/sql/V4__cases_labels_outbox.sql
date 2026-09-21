-- V4: investigation cases, fraud labels (feedback loop), transactional outbox, consumer idempotency,
-- and integration-failure log.

CREATE TABLE fraud_cases (
    case_id            uuid        PRIMARY KEY,
    tenant_id          text        NOT NULL,
    decision_id        uuid        NOT NULL REFERENCES risk_decisions (decision_id),
    transaction_id     text        NOT NULL,
    customer_id        text        NOT NULL,
    status             text        NOT NULL DEFAULT 'PENDING_EXTERNAL'
                       CHECK (status IN ('PENDING_EXTERNAL', 'OPEN', 'IN_PROGRESS', 'CONFIRMED_FRAUD', 'FALSE_POSITIVE', 'CLOSED')),
    priority           text        NOT NULL CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    external_case_ref  text,
    assigned_to        text,
    resolution_note    text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    row_version        integer     NOT NULL DEFAULT 1,
    CONSTRAINT uq_case_per_decision UNIQUE (decision_id)       -- duplicate events cannot create two cases
);
CREATE INDEX ix_cases_queue ON fraud_cases (tenant_id, status, priority, created_at);

-- Labels arrive from chargeback files, analyst outcomes and customer reports, often weeks later.
CREATE TABLE fraud_labels (
    tenant_id       text        NOT NULL,
    transaction_id  text        NOT NULL,
    source          text        NOT NULL CHECK (source IN ('CHARGEBACK', 'ANALYST', 'CUSTOMER_REPORT', 'BATCH_LABEL')),
    label           text        NOT NULL CHECK (label IN ('FRAUD', 'GENUINE')),
    fraud_type      text,
    reported_at     timestamptz NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, transaction_id, source)
);
CREATE INDEX ix_labels_received ON fraud_labels (tenant_id, received_at);

-- Transactional outbox (ADR-002). Written in the same DB transaction as the state change.
CREATE TABLE outbox_events (
    event_id        uuid        PRIMARY KEY,
    tenant_id       text        NOT NULL,
    aggregate_type  text        NOT NULL,
    aggregate_id    text        NOT NULL,
    event_type      text        NOT NULL,
    event_version   integer     NOT NULL,
    topic           text        NOT NULL,
    partition_key   text        NOT NULL,
    payload         jsonb       NOT NULL,
    headers         jsonb       NOT NULL DEFAULT '{}',
    created_at      timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempts        integer     NOT NULL DEFAULT 0,
    last_error      text
);
-- The relay only ever scans unpublished rows: keep that index tiny with a partial predicate.
CREATE INDEX ix_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_aggregate ON outbox_events (tenant_id, aggregate_type, aggregate_id, created_at);

-- Consumer-side idempotency: at-least-once delivery means every consumer must de-duplicate.
CREATE TABLE processed_events (
    consumer_group  text        NOT NULL,
    event_id        uuid        NOT NULL,
    processed_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);

CREATE TABLE integration_failures (
    failure_id      bigserial   PRIMARY KEY,
    tenant_id       text,
    integration     text        NOT NULL,
    operation       text        NOT NULL,
    error_code      text        NOT NULL,
    error_message   text,
    reference_id    text,
    correlation_id  text,
    retry_count     integer     NOT NULL DEFAULT 0,
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz
);
CREATE INDEX ix_integration_failures_open ON integration_failures (integration, occurred_at) WHERE resolved_at IS NULL;
