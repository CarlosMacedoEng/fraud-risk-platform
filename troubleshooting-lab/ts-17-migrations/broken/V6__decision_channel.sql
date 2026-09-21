-- TS-17 BROKEN migration (lab only, never applied to the real schema).
-- Intent: store the transaction channel on the decision for reporting. Mistake: NOT NULL without a default on a
-- populated table, written and tested against an EMPTY developer database.
ALTER TABLE risk_decisions ADD COLUMN channel text NOT NULL;
