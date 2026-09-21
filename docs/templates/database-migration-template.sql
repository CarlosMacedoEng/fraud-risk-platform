-- V<n>__<verb>_<object>.sql   (e.g. V8__add_decision_region_expand.sql)
-- Release: <x.y>   Step: EXPAND | CONTRACT   Ticket: <id>   Rehearsed on: <env, row count, date>
--
-- Rules (MENTORING_AND_ENGINEERING_STANDARDS.md, migrations section):
--  * Never edit a migration that has run anywhere; add a new one.
--  * EXPAND must keep the previous application version working (additive, nullable, no renames).
--  * A data backfill is NOT a migration: run it as a throttled, idempotent job and reconcile
--    (DecisionChannelBackfillService, MIGRATION_AND_UPGRADE_RUNBOOK.md).
--  * CONTRACT (NOT NULL, drop, rename) ships a release later, after reconciliation and after the old version is gone.
--  * Indexes on big tables: CREATE INDEX CONCURRENTLY in a separate, non-transactional migration.
SET lock_timeout = '5s';          -- fail fast instead of queueing behind long transactions and blocking everyone
SET statement_timeout = '15min';  -- bounded, but long enough for a VALIDATE on a large table

-- EXPAND example
ALTER TABLE my_table ADD COLUMN my_column text;          -- nullable: metadata-only, no table rewrite
COMMENT ON COLUMN my_table.my_column IS 'purpose; filled by backfill job X; NOT NULL in V<n+1>';

-- CONTRACT example (separate file, later release)
-- ALTER TABLE my_table ADD CONSTRAINT ck_my_column_nn CHECK (my_column IS NOT NULL) NOT VALID;
-- ALTER TABLE my_table VALIDATE CONSTRAINT ck_my_column_nn;   -- no ACCESS EXCLUSIVE scan
-- ALTER TABLE my_table ALTER COLUMN my_column SET NOT NULL;   -- reuses the validated check (PostgreSQL 12+)
-- ALTER TABLE my_table DROP CONSTRAINT ck_my_column_nn;
