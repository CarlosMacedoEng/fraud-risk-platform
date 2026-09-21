-- TS-17 FIX, step 1 (expand): nullable column, metadata-only change, no table rewrite, no long lock.
-- Application version N+1 writes channel for new decisions; version N ignores it (backward compatible).
ALTER TABLE risk_decisions ADD COLUMN channel text;
