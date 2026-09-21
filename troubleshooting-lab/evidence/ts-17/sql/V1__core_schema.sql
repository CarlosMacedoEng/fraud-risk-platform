-- V1: core scoring schema (tenants, customer replica, transactions, decisions, idempotency).
-- Conventions: every business table is tenant-scoped; enumerations are CHECK constraints so that
-- invalid data is rejected by the database even if an application bug lets it through.

CREATE TABLE tenants (
    tenant_id     text PRIMARY KEY,
    display_name  text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- Replica of the customer master (fed by daily profile files and profile-update events).
CREATE TABLE customers (
    tenant_id         text          NOT NULL REFERENCES tenants (tenant_id),
    customer_id       text          NOT NULL,
    segment           text          NOT NULL,
    home_country      char(2)       NOT NULL,
    tenure_days       integer       NOT NULL CHECK (tenure_days >= 0),
    avg_amount_90d    numeric(14,2) NOT NULL CHECK (avg_amount_90d >= 0),
    risk_tier         text          NOT NULL CHECK (risk_tier IN ('low', 'standard', 'elevated')),
    bound_device_ids  text[]        NOT NULL DEFAULT '{}',
    source            text          NOT NULL DEFAULT 'BATCH' CHECK (source IN ('BATCH', 'API', 'EVENT')),
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, customer_id)
);

-- Every scored or ingested transaction. No FK to customers: real-time traffic may reference a customer
-- not yet present in the nightly replica; that case is handled by the profile fallback.
CREATE TABLE transactions (
    tenant_id            text          NOT NULL REFERENCES tenants (tenant_id),
    transaction_id       text          NOT NULL,
    customer_id          text          NOT NULL,
    account_id           text          NOT NULL,
    event_time           timestamptz   NOT NULL,
    transaction_type     text          NOT NULL CHECK (transaction_type IN ('CARD_PAYMENT', 'TRANSFER')),
    channel              text          NOT NULL CHECK (channel IN ('POS', 'ECOM', 'MOBILE', 'WEB', 'OPEN_BANKING', 'BRANCH')),
    amount               numeric(14,2) NOT NULL CHECK (amount > 0),
    currency             char(3)       NOT NULL,
    card_token           text,
    merchant_id          text,
    mcc                  char(4),
    merchant_country     char(2),
    beneficiary_id       text,
    beneficiary_country  char(2),
    device_id            text,
    ip_address           text,
    ip_country           char(2),
    source               text          NOT NULL DEFAULT 'REALTIME' CHECK (source IN ('REALTIME', 'BATCH')),
    received_at          timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, transaction_id),
    CONSTRAINT chk_card_fields CHECK (transaction_type <> 'CARD_PAYMENT' OR (card_token IS NOT NULL AND merchant_id IS NOT NULL)),
    CONSTRAINT chk_transfer_fields CHECK (transaction_type <> 'TRANSFER' OR beneficiary_id IS NOT NULL)
);

-- Investigation queries ("show me this customer's recent activity") and the PostgreSQL velocity
-- fallback used when Redis is unavailable.
CREATE INDEX ix_transactions_customer_time ON transactions (tenant_id, customer_id, event_time DESC);
CREATE INDEX ix_transactions_card_time ON transactions (tenant_id, card_token, event_time DESC) WHERE card_token IS NOT NULL;
CREATE INDEX ix_transactions_device_time ON transactions (tenant_id, device_id, event_time DESC) WHERE device_id IS NOT NULL;

CREATE TABLE risk_decisions (
    decision_id               uuid          PRIMARY KEY,
    tenant_id                 text          NOT NULL,
    transaction_id            text          NOT NULL,
    decision                  text          NOT NULL CHECK (decision IN ('APPROVE', 'REVIEW', 'DECLINE')),
    risk_score                numeric(7,6)  NOT NULL CHECK (risk_score BETWEEN 0 AND 1),
    risk_level                text          NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    model_probability         numeric(9,8),
    anomaly_percentile        numeric(9,8),
    graph_risk                numeric(7,6),
    rule_points               integer       NOT NULL DEFAULT 0,
    reasons                   jsonb         NOT NULL,
    feature_vector            jsonb         NOT NULL,
    model_version             text,
    strategy_version          text          NOT NULL,
    feature_spec_version      text          NOT NULL,
    challenger_model_version  text,
    challenger_probability    numeric(9,8),
    degraded_modes            text[]        NOT NULL DEFAULT '{}',
    processing_ms             numeric(9,3)  NOT NULL,
    client_id                 text          NOT NULL,
    correlation_id            text,
    created_at                timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_decision_per_transaction UNIQUE (tenant_id, transaction_id),
    CONSTRAINT fk_decision_transaction FOREIGN KEY (tenant_id, transaction_id)
        REFERENCES transactions (tenant_id, transaction_id)
);

-- Keyset pagination for decision listings and the review queue.
CREATE INDEX ix_decisions_tenant_created ON risk_decisions (tenant_id, created_at DESC, decision_id DESC);
CREATE INDEX ix_decisions_review_queue ON risk_decisions (tenant_id, created_at) WHERE decision = 'REVIEW';

CREATE TABLE idempotency_keys (
    client_id        text        NOT NULL,
    idempotency_key  text        NOT NULL,
    tenant_id        text        NOT NULL,
    request_hash     char(64)    NOT NULL,
    status           text        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    decision_id      uuid,
    response_body    jsonb,
    created_at       timestamptz NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    PRIMARY KEY (client_id, idempotency_key)
);
CREATE INDEX ix_idempotency_created ON idempotency_keys (created_at);  -- housekeeping job

INSERT INTO tenants (tenant_id, display_name) VALUES
    ('aldermoor-bank', 'Aldermoor Bank (fictional)'),
    ('quillon-pay', 'Quillon Pay (fictional)');
