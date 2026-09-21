-- TS-17 FIX, step 3 (contract), only after the backfill reports 0 remaining rows.
-- NOT VALID + VALIDATE avoids scanning the table under an ACCESS EXCLUSIVE lock; SET NOT NULL then reuses the
-- validated CHECK constraint (PostgreSQL 12+) instead of rescanning.
ALTER TABLE risk_decisions ADD CONSTRAINT ck_decision_channel_nn CHECK (channel IS NOT NULL) NOT VALID;
ALTER TABLE risk_decisions VALIDATE CONSTRAINT ck_decision_channel_nn;
ALTER TABLE risk_decisions ALTER COLUMN channel SET NOT NULL;
ALTER TABLE risk_decisions DROP CONSTRAINT ck_decision_channel_nn;
