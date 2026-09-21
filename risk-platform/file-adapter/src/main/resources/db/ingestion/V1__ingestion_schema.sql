-- file-adapter owns the "ingestion" schema (separate Flyway history table). It never writes to the
-- decision-service tables: results flow as events; reconciliation reads the reporting views.

CREATE TABLE file_runs (
    run_id               uuid        PRIMARY KEY,
    tenant_id            text,
    file_type            text,
    file_name            text        NOT NULL,
    business_date        date,
    sequence             integer,
    sha256               char(64)    NOT NULL,
    status               text        NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS',
                                                               'REJECTED', 'FAILED')),
    rejection_reason     text,
    records_total        integer     NOT NULL DEFAULT 0,
    records_valid        integer     NOT NULL DEFAULT 0,
    records_quarantined  integer     NOT NULL DEFAULT 0,
    records_duplicate    integer     NOT NULL DEFAULT 0,
    events_published     integer     NOT NULL DEFAULT 0,
    started_at           timestamptz NOT NULL DEFAULT now(),
    finished_at          timestamptz,
    processing_ms        integer,
    report               jsonb
);
-- The same content can be claimed/processed only once (duplicate-file protection, multi-instance safe).
CREATE UNIQUE INDEX uq_file_runs_content ON file_runs (sha256)
    WHERE status IN ('PROCESSING', 'COMPLETED', 'COMPLETED_WITH_ERRORS');
-- A file name (type/tenant/date/sequence) is accepted once; corrections must use a new sequence number.
CREATE UNIQUE INDEX uq_file_runs_identity ON file_runs (tenant_id, file_type, business_date, sequence)
    WHERE status IN ('COMPLETED', 'COMPLETED_WITH_ERRORS');
CREATE INDEX ix_file_runs_recent ON file_runs (started_at DESC);

CREATE TABLE quarantined_records (
    run_id       uuid    NOT NULL REFERENCES file_runs (run_id),
    line_number  integer NOT NULL,
    raw_record   text    NOT NULL,
    errors       jsonb   NOT NULL,
    PRIMARY KEY (run_id, line_number)
);

-- Record-level duplicate detection across files.
CREATE TABLE ingested_keys (
    tenant_id    text        NOT NULL,
    file_type    text        NOT NULL,
    natural_key  text        NOT NULL,
    run_id       uuid        NOT NULL,
    ingested_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, file_type, natural_key)
);

CREATE TABLE reconciliation_records (
    tenant_id          text          NOT NULL,
    business_date      date          NOT NULL,
    transaction_id     text          NOT NULL,
    category           text          NOT NULL CHECK (category IN ('MATCHED', 'AMOUNT_MISMATCH', 'SETTLED_BUT_DECLINED',
                                                                  'NOT_SCORED', 'APPROVED_NOT_SETTLED', 'REVERSED')),
    platform_decision  text,
    platform_amount    numeric(14,2),
    settled_amount     numeric(14,2),
    settlement_status  text,
    run_id             uuid          NOT NULL,
    created_at         timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, business_date, transaction_id)
);
CREATE INDEX ix_recon_category ON reconciliation_records (tenant_id, business_date, category);
