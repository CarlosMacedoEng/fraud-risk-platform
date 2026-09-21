-- Release 2.1, step 3 of 3 (CONTRACT). NOT on the Flyway path yet: moved to db/migration only after
--   (a) every instance runs release >= 2.0 (writes channel), and
--   (b) GET /v1/admin/tenants/{t}/migrations/decision-channel reports remaining = 0 for every tenant.
-- NOT VALID + VALIDATE avoids a full scan under ACCESS EXCLUSIVE; SET NOT NULL then reuses the validated
-- constraint (PostgreSQL 12+). Rehearsed on a scratch database in troubleshooting-lab (TS-17).
SET lock_timeout = '5s';
ALTER TABLE risk_decisions ADD CONSTRAINT ck_decision_channel_nn CHECK (channel IS NOT NULL) NOT VALID;
ALTER TABLE risk_decisions VALIDATE CONSTRAINT ck_decision_channel_nn;
ALTER TABLE risk_decisions ALTER COLUMN channel SET NOT NULL;
ALTER TABLE risk_decisions DROP CONSTRAINT ck_decision_channel_nn;
