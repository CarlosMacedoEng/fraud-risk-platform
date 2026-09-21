-- Release 2.0, step 1 of 3 (EXPAND). See docs/MIGRATION_AND_UPGRADE_RUNBOOK.md.
-- Store the payment channel on the decision so channel-level decision reporting and future partitioning do not
-- need a join to transactions. Nullable: metadata-only change (no table rewrite, no long ACCESS EXCLUSIVE lock),
-- and release 1.x code, which does not know the column, keeps working (rollback-safe).
ALTER TABLE risk_decisions ADD COLUMN channel text;
COMMENT ON COLUMN risk_decisions.channel IS
  'Release 2.0: written for new decisions; historical rows filled by the channel backfill job; NOT NULL in V7 (contract, later release)';
