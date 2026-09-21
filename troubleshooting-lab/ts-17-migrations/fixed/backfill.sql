-- TS-17 FIX, step 2 (backfill): batched, restartable, run as a job between the two deployments.
-- Each batch is a short transaction, so row locks are held briefly and replication lag stays bounded.
DO $$
DECLARE n integer;
BEGIN
  LOOP
    UPDATE risk_decisions d SET channel = t.channel
      FROM transactions t
     WHERE t.tenant_id = d.tenant_id AND t.transaction_id = d.transaction_id
       AND d.decision_id IN (SELECT decision_id FROM risk_decisions WHERE channel IS NULL LIMIT 5000);
    GET DIAGNOSTICS n = ROW_COUNT;
    RAISE NOTICE 'backfilled % rows', n;
    EXIT WHEN n = 0;
    COMMIT;
  END LOOP;
END $$;
