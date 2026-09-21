# Migration and Upgrade Runbook — Release 1.x → 2.0

> **Status.** Implemented and rehearsed locally on the lab database (≈ 600,000 synthetic decisions, Docker
> Compose, one laptop). All numbers below come from that rehearsal; the scripts and raw evidence are in
> [`migration-rehearsal/`](../migration-rehearsal/). Nothing was run against a customer environment. Timings on
> real infrastructure (RDS gp3/io2) will differ: **the rehearsal must be repeated in the customer's staging
> environment with production-sized data before go-live.**

## 1. What changes in release 2.0

| Area | Change | Compatibility |
|---|---|---|
| Schema | `V6__decision_channel_expand.sql`: nullable `risk_decisions.channel` | Expand-only: release 1.x keeps working on the new schema (verified) |
| Code | New decisions store `channel` (sub-select on the transaction's PK, same DB transaction) | 1.x ignores the column |
| Data | Online backfill of historical decisions + reconciliation endpoint | Idempotent, restartable, throttled |
| Schema (later, 2.1) | `db/pending/V7__decision_channel_contract.sql`: `NOT NULL` | Only after the gate (§5); breaks 1.x writes, so 1.x must be gone |
| Models | Unchanged in 2.0. Model upgrades use the model lifecycle (§7) | – |

Why this change: channel-level decision reporting (and future partitioning) without joining `transactions`.
It is also a deliberately typical "add a column to a big, hot table" change, the kind of change that breaks go-lives
when done in one step (TS-17).

## 2. Roles and communication

| Role | Responsibility |
|---|---|
| Change owner (implementation engineer) | Runs the steps, owns the go / no-go at each gate |
| Customer DBA / platform team | Backup confirmation, storage and replication monitoring, approves the backfill window |
| Customer fraud operations | Informed; no functional change for analysts in 2.0 |
| Support on call | Watches alerts during and 24 h after |

Customer notice: 5 working days before (change ticket + this runbook); start / end notifications. Template:
[docs/customer/go-live-communication.md](customer/go-live-communication.md) (same structure).

## 3. Pre-checks (T-1 day and T-0)

| # | Check | Command / evidence | Rehearsal result |
|---|---|---|---|
| P1 | Current schema version = 5 | `SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1` | 5 (`reporting views`) |
| P2 | Data volume known | `SELECT tenant_id, count(*) FROM risk_decisions GROUP BY 1`; table size | Aldermoor 450,749, Quillon 149,411; 1,156 MB |
| P3 | Fresh backup / snapshot | RDS snapshot (prod); `pg_dump -Fc` (lab) | 33 s, 449 MB |
| P4 | Rollback image available | previous release tag in the registry | `decision-service:rel1` built from the Stage 10 commit |
| P5 | No other change in the window | change calendar | – |
| P6 | Storage headroom ≥ 2.5 × table size | backfill rewrites every row (§6) | table grew 1,156 → 2,532 MB |
| P7 | Alerts green, error budget available | Grafana, Alertmanager | – |

## 4. Procedure

### Step 1 — Deploy 2.0 (applies V6)
Rolling update (Kubernetes: `maxUnavailable: 0`). Flyway takes an advisory lock; the first pod applies V6.

*Rehearsal:* V6 applied in **9 ms** (metadata-only `ADD COLUMN`); 2.0 ready 21 s after the deployment started.

**Verify:** new decisions have a channel.
```bash
curl -X POST /v1/decisions ...   # then:
SELECT channel FROM risk_decisions WHERE transaction_id = '<tx>';   -- rehearsal: POS
```
**Go/no-go 1:** readiness green on all pods, smoke decision OK, no increase in 5xx or latency.

### Step 2 — Reconciliation baseline
```bash
GET /v1/admin/tenants/{tenant}/migrations/decision-channel
```
*Rehearsal:* `{"total":450750,"remaining":450749,"mismatched":0}` (Aldermoor; the one filled row is the smoke
decision).

### Step 3 — Backfill (online, throttled)
```bash
POST /v1/admin/tenants/{tenant}/migrations/decision-channel/backfill?batchSize=500&pauseMs=1000
```
Run per tenant, during the lowest-traffic window agreed with the customer; watch the p99 latency, COMMIT
latency (`pg_stat_statements`), replication lag and storage. **Stop criterion:** p99 > 100 ms for 5 minutes →
stop (the job is restartable), widen the pause, resume. The throttle settings come from the rehearsal (§6):
the aggressive settings hurt scoring badly.

### Step 4 — Reconciliation after backfill (gate for V7)
`remaining = 0` and `mismatched = 0` for every tenant (`readyForContract: true`).
*Rehearsal:* both tenants `remaining 0, mismatched 0`.

### Step 5 — Maintenance
`VACUUM (ANALYZE) risk_decisions` after the backfill (the backfill leaves one dead tuple version per row;
autovacuum will catch up, but a manual run makes the plan statistics fresh).

### Step 6 — Observation period
24 h with 2.0 at full traffic before any contract step.

## 5. The contract step (release 2.1, not executed)

Pre-conditions, all required:
1. No instance of release 1.x is running anywhere (after V7, a 1.x instance would fail to insert decisions).
2. Reconciliation `readyForContract: true` for every tenant, **re-checked after any rollback** (see §8).
3. Move `db/pending/V7__decision_channel_contract.sql` into `db/migration/` in release 2.1.

V7 uses `CHECK … NOT VALID` → `VALIDATE CONSTRAINT` → `SET NOT NULL` → drop the check, with
`lock_timeout = 5s`. Verified in the integration test `ReleaseMigrationIntegrationTest` (dry run inside a
rolled-back transaction) and on the TS-17 scratch database.

**Alternative worth discussing with the customer (not implemented):** skip the history backfill entirely.
Add `CHECK (channel IS NOT NULL) NOT VALID` and never validate it: PostgreSQL enforces it for new and updated rows
only. Reports use `COALESCE(d.channel, t.channel)` for old rows. That avoids rewriting ~1 GB of rows (§6).

## 6. What the rehearsal taught (the important part)

### 6.1 The backfill rate decides whether scoring suffers
Same load (50 rps of scoring), Aldermoor tenant (~465,000 rows), same JVM warmth:

| Backfill settings | Rows/s | Scoring p99 (client) | Failed requests | Duration (full tenant) | Evidence |
|---|---|---|---|---|---|
| none (reference) | – | **43.9 ms** | 0% | – | `evidence/k6-reference.txt` |
| batch 5,000, pause 50 ms | ~18,800 | **607 ms** | 0.51% | 24 s | `evidence/k6-during-backfill.txt` |
| batch 1,000, pause 100 ms, `max_wal_size` 1 GB | ~7,000 | 509 ms | 0.31% | 67 s | `evidence/tuning-b1000-p100-wal1GB/` |
| batch 1,000, pause 100 ms, 4 GB (**confounded**: setup checkpoint inside the window) | ~7,000 | 1,003 ms | 1.79% | 67 s | `evidence/tuning-b1000-p100-wal4GB/` |
| batch 1,000, pause 100 ms, 4 GB, checkpoint before measuring | ~7,300 | 819 ms | 1.39% | 64 s | `evidence/tuning-b1000-p100-wal4GB-ckpt/` |
| **batch 500, pause 1,000 ms** | **~490** | **27.4 ms** | **0%** | ~16 min (estimated from 100 batches) | `evidence/tuning-b500-p1000-wal4GB-ckpt/` |

**Why.** PostgreSQL logged hot-path statements `> 200 ms` during the fast runs: `COMMIT` up to 1.5 s and
`INSERT INTO idempotency_keys` up to 1.2 s. These statements touch rows the backfill never locks (new
idempotency keys, commits), so row locking is an unlikely cause; wait events were **not** sampled, so the
WAL explanation below is the best-supported inference, not a measured fact. The backfill rewrites every decision row (MVCC
creates a new version of the ~1.5 KB row), generating hundreds of MB of WAL in about a minute, and commits on the
scoring path wait for WAL flushes. On this laptop's virtualised disk that shows up as fsync stalls; on real
storage the threshold will be different, but the mechanism is the same.

**Hypotheses that were tested and rejected.**
* "Checkpoints too frequent" (the log said so at the default `max_wal_size = 1GB`). Raising it to 4 GB removed
  the warning but **not** the latency impact.
* One tuning run was **confounded by the setup itself**: the reset `UPDATE` of 465,000 rows triggered a
  checkpoint in the middle of the measurement. The script now issues a `CHECKPOINT` before measuring.

**Rule for the runbook:** throttle by *effect* (p99, COMMIT latency, replication lag), not by batch size; start
gentle; plan the duration accordingly (≈ 16 min for this tenant at a safe rate on this hardware).

### 6.2 The table doubled in size
1,156 MB → 2,532 MB after the backfill (plus the resets during tuning). `VACUUM` makes the space reusable but does
not return it to the operating system (`VACUUM FULL` / `pg_repack` would, with a lock or extra tooling).
Hence pre-check P6, and the "no backfill" alternative in §5.

### 6.3 The reconciliation query failed right after the backfill
`GET …/migrations/decision-channel` hit the service-wide `statement_timeout = 2s` (the hot-path guard): a full
count with a join, on a table full of dead tuples. The backfill had succeeded (450,749 rows), but the API returned
503 "database unavailable". **Fixed:** the reconciliation runs with `SET LOCAL statement_timeout = '60s'`, and
a failed reconciliation no longer masks committed backfill work.

### 6.4 `VACUUM` failed: `could not resize shared memory segment … No space left on device`
Docker's default `/dev/shm` is 64 MB; parallel maintenance needs more. **Fixed** in compose
(`shm_size: 256mb`); in Kubernetes the equivalent is an `emptyDir` with `medium: Memory` on `/dev/shm`. Managed RDS
is not affected.

## 7. Model upgrades (same principles, different mechanism)
The active strategy pins the champion model and an optional challenger
(`"model": {"version": …, "challengerVersion": …, "challengerMode": "SHADOW"}`). Quillon strategy 1.1.0 already
scores model 1.1.0 in shadow. Upgrade path:

1. `POST /models/{v}/register`: checksums verified by loading the model.
2. Shadow period: challenger scores are stored with each decision (`challenger_probability`); compare offline
   (score distribution, alert rate at the operating threshold, labelled outcomes once mature).
3. New strategy version pointing to the new champion → simulate (paired, §TS-13) → canary % → 100%.
4. Rollback = previous strategy version (one API call, audited). The model files of the previous version stay
   loaded.

This path was exercised in Stage 4 (governance tests), not in this rehearsal.

## 8. Rollback

| Situation | Action | Rehearsal |
|---|---|---|
| 2.0 misbehaves before V7 | Redeploy release 1.x. **No schema rollback needed** (V6 is additive) | 1.x started on the V6 schema: Flyway `Successfully validated 6 migrations` (future migrations are ignored by default), readiness 200, scoring HTTP 200 |
| Decisions written by 1.x during the rollback | They have `channel = NULL` → re-run the backfill before V7 | 1.x wrote `NULL`; the re-run updated 1 row → `readyForContract: true` |
| Backfill hurts scoring | Stop it (restartable), widen the pause | §6.1 |
| After V7 | Rolling back to 1.x is **not** possible without dropping the constraint — that is why V7 ships a release later | – |
| Data corruption (not expected) | Restore snapshot / PITR to the pre-change point; replay decisions from the outbox if needed | backup taken (P3), restore not rehearsed |

## 9. Post-migration checklist
- [ ] Reconciliation `remaining 0 / mismatched 0` for all tenants
- [ ] p95/p99 within SLO for 24 h; no new alerts
- [ ] `VACUUM (ANALYZE)` done; table size recorded
- [ ] Customer notified of completion; change ticket closed with evidence
- [ ] Release 2.1 (V7) scheduled only after the observation period

## 10. Limitations
* Rehearsed on one laptop with synthetic data; storage behaviour differs from RDS.
* Restore from backup and the V7 contract on the full-size lab table were not executed (V7 was dry-run in an
  integration test and rehearsed on the TS-17 scratch database).
* The backfill has a fixed throttle; an adaptive throttle (pause when p99 or replication lag exceeds a limit) is
  a future improvement.
* Migrations still run at application start; a pre-deployment migration Job is described in
  AWS_AND_KUBERNETES.md but not implemented.
