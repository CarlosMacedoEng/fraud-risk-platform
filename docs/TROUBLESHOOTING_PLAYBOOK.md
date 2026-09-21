# Troubleshooting Playbook

> **Scope and honesty note.** Every incident below was reproduced in a local lab (Docker Compose, 2 vCPU for
> the decision service, synthetic data, fictional customers Aldermoor Bank and Quillon Pay). None of these are
> production incidents and none involve real banks. The numbers are real measurements from the lab runs; the
> raw evidence for each one is in [`troubleshooting-lab/evidence/`](../troubleshooting-lab/evidence/). Customer
> messages are templates showing tone and content, not messages that were sent.
>
> The lab found **real defects** in this codebase, which were then fixed (journal J-25 to J-32). Those are the
> most useful parts of this document: they were found by investigation, not written into the scenario.

## Contents
1. [How to use this playbook](#how-to-use-this-playbook)
2. [The first 15 minutes (any incident)](#the-first-15-minutes-any-incident)
3. [Incident index](#incident-index)
4. [Incidents TS-01 … TS-18](#ts-01)
5. [What the lab found that was not planned](#what-the-lab-found-that-was-not-planned)
6. [Tool reference](#tool-reference)

## How to use this playbook

Each incident follows the same structure: **symptoms → impact → hypotheses → investigation (commands) →
evidence → root cause → remediation → prevention → customer communication → follow-up.**

Reproduce any incident:

```bash
# decision-service with the lab profile (enables /lab/faults; never enabled outside the lab)
DECISION_PROFILES=lab docker compose -f deploy/docker-compose.yml up -d decision-service
troubleshooting-lab/ts-01-slow-query.sh          # evidence -> troubleshooting-lab/evidence/ts-01/
```

`troubleshooting-lab/lib.sh` holds the shared helpers: `load <rps> <duration>` (k6 open-model load),
`prom '<PromQL>'`, `sql`, `lab_fault <POINT> '<json>'`, `lab_clear`, and `evidence <id>`, which resets that
incident's evidence folder.

Severity model (used in the alert rules and in customer messages):

| Sev | Definition (synthetic SLA) | Customer update cadence |
|---|---|---|
| SEV1 | Scoring unavailable or wrong decisions at scale (e.g. mass declines) | Every 30 min, bridge call |
| SEV2 | Scoring degraded (latency SLO breached, fallback decisions, review flood) or a data flow stopped | Every 60 min |
| SEV3 | Single integration or batch affected, workaround exists | Daily / at resolution |

## The first 15 minutes (any incident)

1. **Scope.** Which tenant(s), which channel, since when? Compare against deployments and configuration changes
   (`GET /v1/admin/tenants/{t}/deployments`, `audit_events`). A decision-mix change that coincides with a change
   is TS-13/TS-14 until proven otherwise.
2. **Golden signals** (Grafana "Fraud platform" dashboard): decision latency p95/p99, 5xx and 503 OVERLOADED,
   degraded-decision share by mode, decision mix, admission rejections, circuit-breaker states, Hikari pending,
   outbox age, consumer lag **and** assigned partitions.
3. **Degraded modes tell you which dependency.** `risk_decisions_degraded_total{mode=...}`:
   `FEATURE_STORE_DEGRADED` → Redis (TS-12), `MODEL_UNAVAILABLE` → inference (TS-11),
   `DEVICE_RISK_UNAVAILABLE` → vendor (TS-06). **No** degraded mode but a changed mix → TS-18.
4. **Stabilise before diagnosing.** Rollback (strategy, deployment) or remove the trigger (fault, bad file) if
   that is safe. Capture evidence first when it is cheap: thread dump, JFR, heap info, `pg_stat_activity`.
5. **Communicate** within the severity cadence, even if the cause is unknown ("what we know, what we are doing,
   next update at").

## Incident index

| ID | Incident | Reproduction | Outcome in the lab | Real defect found? |
|---|---|---|---|---|
| [TS-01](#ts-01) | Slow database query | drop an index | 0.18 ms → 66 ms query; fixed with `CREATE INDEX CONCURRENTLY` | – |
| [TS-02](#ts-02) | Connection-pool exhaustion | +400 ms inside the decision transaction | 20/20 connections, 17.5% failed requests | – |
| [TS-03](#ts-03) | High CPU | +15 ms CPU per request | p99 890 ms; JFR: 61% samples in one method | – |
| [TS-04](#ts-04) | Memory pressure / OOM | 650 MB heap leak | JVM terminated on OOM, heap dump, service down (no restart policy) | restart policy missing |
| [TS-05](#ts-05) | Thread contention | global lock, 10 ms per request | p99 793 ms; classic thread dump showed nothing, JFR did | – |
| [TS-06](#ts-06) | Slow downstream (device vendor) | +250 ms vendor latency | circuit opened, 48% degraded, latency protected | – |
| [TS-07](#ts-07) | Consumer lag | case management +3 s | 972 records dead-lettered, redriven, no case lost | **yes**: broker coordinator fault (J-25), consumer throughput (J-27), DLT flooding (J-31) |
| [TS-08](#ts-08) | Duplicate event processing | replay 5 min of events | 1,926 duplicates detected, 0 duplicate cases | – |
| [TS-09](#ts-09) | Malformed input file | decimal commas, missing timestamps | file rejected (20% invalid > 5%), quarantine report | – |
| [TS-10](#ts-10) | Schema mismatch | swapped columns | file rejected, header diff in report | – |
| [TS-11](#ts-11) | Model timeout | explanation service 6 s; inference 80 ms | graceful degradation; fallback floods review (55% REVIEW) | – |
| [TS-12](#ts-12) | Redis unavailable | `docker stop redis` under load | breakers + bounded fallback worked; **rebuild endpoint failed** | **yes**: Redis connection churn → port exhaustion (J-26); wrong persistence assumption (J-30) |
| [TS-13](#ts-13) | False-positive (decline) spike | superseded strategy promoted | small live effect; paired simulation 2.8× declines; rollback | method issue (J-28) |
| [TS-14](#ts-14) | Review-queue spike | threshold typo, 25% canary | canary 89% vs control 74% REVIEW; rollback | load-test artifact (J-28) |
| [TS-15](#ts-15) | Latency regression after deployment | cold JVM, same build | cold p95 327 ms vs warm 15 ms; C2 JIT 20.6% CPU | – (J-22 confirmed after J-26 fix) |
| [TS-16](#ts-16) | Failed deployment | bad secret; wrong models path | **model-less instance reported ready** → fixed | **yes** (J-29) |
| [TS-17](#ts-17) | Broken migration | `NOT NULL` column on populated table | migration failed + rolled back; expand/contract fix | – |
| [TS-18](#ts-18) | Data inconsistency | Redis state lost | silent: REVIEW 63.6% → 1.6%, no alert → alert added | **yes**: monitoring gap (J-32) |

---

<a id="ts-01"></a>
## TS-01 Slow database query

**Symptoms.** The analyst decision-listing API (`GET /v1/decisions`) slows from ~15 ms to ~70 ms; no errors.

**Impact.** Case-review UI slower; at scale, sequential scans on `risk_decisions` (the largest table) compete
for I/O and CPU with the scoring path. Scoring is not affected in the lab.

**Hypotheses.** (1) Missing or invalid index. (2) Stale statistics → bad plan. (3) Lock waits. (4) Table bloat.

**Investigation.**
```bash
sql -c "SELECT query, calls, mean_exec_time FROM pg_stat_statements ORDER BY mean_exec_time DESC LIMIT 5"
sql -c "EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM risk_decisions WHERE tenant_id='aldermoor-bank'
        ORDER BY created_at DESC, decision_id DESC LIMIT 50"
sql -c "SELECT indexrelname, idx_scan FROM pg_stat_user_indexes WHERE relname='risk_decisions'"
sql -c "SELECT pid, wait_event_type, wait_event, state, query FROM pg_stat_activity WHERE datname='riskplatform'"
```

**Evidence** ([ts-01](../troubleshooting-lab/evidence/ts-01/)). Plan before: `Index Scan using
ix_decisions_tenant_created`, 0.178 ms. After the index was dropped: `Parallel Seq Scan` (3 workers × ~88k
rows) + top-N heapsort, **66.3 ms**. API latency 15 ms → 66–71 ms. No lock waits.

**Root cause.** The keyset-pagination index `ix_decisions_tenant_created` was missing (in the lab it was
dropped deliberately; in real life: failed migration, manual change, index marked invalid by an interrupted
`CREATE INDEX CONCURRENTLY`).

**Remediation.** `CREATE INDEX CONCURRENTLY ix_decisions_tenant_created ON risk_decisions (tenant_id,
created_at DESC, decision_id DESC);` builds without blocking writes. API back to 9–26 ms.

**Prevention.** Indexes only via migrations; schema-drift check in the deployment pipeline (compare
`pg_indexes` with the migration baseline); check `indisvalid` after concurrent builds; alert on
`pg_stat_statements` mean time regression per query id.

**Customer communication.** "Between 10:02 and 10:09 UTC the decision search in the case-review screens was
slower than usual (about 70 ms instead of 15 ms). Real-time scoring was not affected. The cause was a missing
database index, which was rebuilt online without downtime. We have added a check that detects schema
differences before and after each deployment."

**Follow-up.** Add the schema-drift check to the go-live checklist; review which other queries depend on
single indexes.

---

<a id="ts-02"></a>
## TS-02 Connection-pool exhaustion

**Symptoms.** Latency jumps (client p50 600 ms), 17.5% of requests fail, degraded decisions rise to 21.6%.
CPU is low. Database metrics look calm.

**Impact.** SEV1/SEV2: a sixth of the payments fail to get a decision (the caller's fallback applies), the
rest wait ~0.6 s.

**Hypotheses.** (1) Slow queries holding connections. (2) Slow non-database work inside a transaction.
(3) Pool too small for the arrival rate. (4) Connection leak.

**Investigation.**
```bash
prom 'max(hikaricp_connections_active)'; prom 'max(hikaricp_connections_pending)'
# virtual threads are NOT in jcmd Thread.print - use the JSON dump
docker exec $DS jcmd 1 Thread.dump_to_file -format=json /dumps/ts02.json
sql -c "SELECT state, wait_event, now()-xact_start AS xact_age, left(query,60) FROM pg_stat_activity
        WHERE application_name='decision-service'"
```

**Evidence** ([ts-02](../troubleshooting-lab/evidence/ts-02/)). Hikari active 20/20 and 12 pending. The
virtual-thread dump shows **20 threads holding a connection** inside the slow step and **10 waiting in
`HikariPool.getConnection`**. PostgreSQL shows the held connections as `idle`, not `idle in transaction`,
because the transaction's `BEGIN` is only sent with the first statement: from the database side nothing
looks wrong. k6: 2,687 requests at 59.2/s, 17.45% failed, client p99 748 ms.

**Root cause.** 400 ms of non-database work inside the persistence transaction. Little's law: 60 req/s ×
0.4 s = 24 connections needed > 20 in the pool. Every extra request queues for a connection.

**Remediation.** Remove the slow work from the transaction (lab: fault cleared). Short-term mitigation if the
code cannot change immediately: shed load (admission control already does) rather than enlarging the pool,
which moves the bottleneck to PostgreSQL.

**Prevention.** Rule: no remote calls or waits inside `@Transactional` / `TransactionTemplate`; Hikari
`leakDetectionThreshold` in non-prod; alert `DbPoolPending` (pending > 5 for 2 min, already configured);
size the pool from Little's law and measure it under load (Stage 8: 0 pending at 150 TPS).

**Customer communication.** "From 10:15 to 10:16 UTC about 17% of scoring requests timed out and your gateway
applied its fallback rule. The cause was a slow step inside our database transaction, which exhausted the
database connection pool. The step was removed and scoring returned to normal immediately. We will share the
list of decisions made by your fallback during that window for review."

**Follow-up.** Code-review checklist item for transaction scope; export the fallback-decided transactions for
the customer's review team.

---

<a id="ts-03"></a>
## TS-03 High CPU

**Symptoms.** p95 449 ms and p99 890 ms at only 40 rps; 9.9% of decisions degraded (budget timeouts);
no errors.

**Impact.** SEV2: latency SLO (p95 ≤ 100 ms) breached; degraded decisions have less signal.

**Hypotheses.** (1) Code regression on the hot path. (2) GC thrashing. (3) JIT (cold JVM, see TS-15).
(4) Noisy neighbour / CPU throttling.

**Investigation.**
```bash
prom 'max(process_cpu_usage{application="decision-service"})'
prom 'sum(rate(jvm_gc_pause_seconds_sum[1m]))'            # rule out GC
docker exec $DS jcmd 1 JFR.start duration=30s settings=profile filename=/dumps/ts03.jfr
docker exec $DS jfr view hot-methods /dumps/ts03.jfr
docker stats --no-stream                                  # container CPU vs limit (throttling)
```

**Evidence** ([ts-03](../troubleshooting-lab/evidence/ts-03/)). JFR hot methods:
`FaultInjector.apply` **61.2%** of execution samples; the next method has 1.35%. GC pause time negligible.

**Root cause.** 15 ms of extra CPU per request on the hot path. At the normal cost of 11.7 CPU-ms per
request (Stage 8 calibration), 40 rps needed ~0.5 CPU; the regression added ~0.6 CPU, and the queueing on
2 vCPU produced the tail latency.

**Remediation.** Remove or optimise the hot code path (lab: fault cleared); roll back the release if the
regression came with a deployment.

**Prevention.** Measure CPU-ms per request per release (the calibration scenario in `perf/`) and fail the
pipeline on a >20% regression; continuous profiling (JFR streaming or async-profiler) in production.

**Customer communication.** "Scoring latency was elevated between 10:17 and 10:18 UTC (95th percentile about
450 ms against a 100 ms target). No requests failed. A code path consumed more CPU than expected; it has been
removed. We now measure CPU cost per request for every release before deployment."

**Follow-up.** Add the per-release CPU budget to the release checklist.

---

<a id="ts-04"></a>
## TS-04 Memory pressure / OutOfMemoryError

**Symptoms.** A burst of `connection reset by peer`; the decision service container is gone. k6: 2,492
requests, **32.4% failed**.

**Impact.** SEV1: scoring unavailable for the single instance until it was restarted manually (~2 min: exit at
17:38:45, restarted 17:40:47).

**Hypotheses.** (1) Heap leak. (2) Native memory (direct buffers, threads). (3) Container memory limit
(OOM-killed by the kernel, not the JVM).

**Investigation.**
```bash
docker inspect $DS --format '{{.State.Status}} exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}}'
docker logs --tail 20 $DS                       # JVM message vs silent kill
docker exec $DS jcmd 1 GC.heap_info             # before it dies: occupancy after GC trending up?
prom 'jvm_memory_used_bytes{area="heap"}'
```

**Evidence** ([ts-04](../troubleshooting-lab/evidence/ts-04/)). `status=exited exit=3 oomKilled=false`, log
tail: `Terminating due to java.lang.OutOfMemoryError: Java heap space`. `oomKilled=false` + JVM message means
the **JVM** gave up (`-XX:+ExitOnOutOfMemoryError`), not the kernel. A 625 MB heap dump
(`ts-04-oom.hprof`, not committed) was written by `-XX:+HeapDumpOnOutOfMemoryError`. The heap filled faster
than the leaked bytes suggested: the leak used 1 MB arrays and G1 regions are 1 MB, so each array became a
*humongous* object occupying two regions.

**Root cause.** Heap leak (lab: a list retaining 50 MB chunks). Second finding: the container had **no restart
policy**, so the correct fail-fast behaviour turned into a full outage.

**Remediation.** Restart (now automatic: `restart: unless-stopped` added to compose; Kubernetes restarts by
default); analyse the heap dump (Eclipse MAT dominator tree → retaining path). In the lab the leak source is
known by construction, so the dump was not analysed.

**Prevention.** Keep `ExitOnOutOfMemoryError` (a JVM limping after OOM is worse than a restart); alert on
heap occupancy *after GC* > 80%; more than one replica so a crash does not stop scoring; ship heap dumps to
durable storage (the container file system is lost on reschedule).

**Customer communication.** "At 10:18 UTC one scoring instance stopped after running out of memory; requests
failed for about two minutes until it was restarted. We captured diagnostic data and are analysing the cause. The
service now restarts automatically, and in production at least two instances run at all times."

**Follow-up.** Heap-dump analysis report; replica count and PodDisruptionBudget are set in the Kubernetes
manifests (3 replicas minimum, `minAvailable: 2`, AWS_AND_KUBERNETES.md).

---

<a id="ts-05"></a>
## TS-05 Thread contention (and virtual-thread pinning)

**Symptoms.** At 60 rps: p95 490 ms, p99 793 ms, 14% degraded, 0.4% failed, admission control rejecting
requests; CPU not saturated.

**Impact.** SEV2: latency SLO breached with plenty of idle CPU; throughput capped.

**Hypotheses.** (1) Lock contention. (2) Pool exhaustion (TS-02). (3) Virtual-thread pinning (Java 21:
blocking inside `synchronized` pins the carrier thread).

**Investigation.**
```bash
docker exec $DS jcmd 1 Thread.print | grep -c BLOCKED        # classic dump: carrier/platform threads only
docker exec $DS jcmd 1 JFR.start duration=30s settings=profile filename=/dumps/ts05.jfr
docker exec $DS jfr summary /dumps/ts05.jfr | grep -E "JavaMonitorEnter|VirtualThreadPinned"
docker exec $DS jfr print --events jdk.JavaMonitorEnter /dumps/ts05.jfr | grep monitorClass | sort | uniq -c
```

**Evidence** ([ts-05](../troubleshooting-lab/evidence/ts-05/)). The classic thread dump showed **0 BLOCKED
carrier threads**, so it looked innocent. JFR: 21 `jdk.JavaMonitorEnter` events (17 on a `java.lang.Object`
monitor) and 6 `jdk.VirtualThreadPinned` events. Admission control rejected ~12 requests in one minute.

**Root cause.** A global `synchronized` block held for 10 ms per request, which serialises the service to at
most ~100 rps. Sleeping or blocking inside it pins the virtual thread's carrier, so with 2 carriers the
remaining work queues behind it.

**Remediation.** Remove the global lock (lab: fault cleared). In real code: narrow the critical section, use
per-key locks or `ReentrantLock` (does not pin on Java 21), or move to lock-free structures.

**Prevention.** JFR contention events in the standard diagnostic bundle; `-Djdk.tracePinnedThreads=short`
in non-prod; code-review rule against `synchronized` around I/O. JDK 24+ (JEP 491) removes most pinning.

**Customer communication.** "Scoring latency was elevated between 10:21 and 10:22 UTC. A shared lock in our
service limited how many requests could be processed at once. The code was corrected. No decisions were lost."

**Follow-up.** Grep the codebase for `synchronized` around blocking calls; add the pinning check to CI tests.

---

<a id="ts-06"></a>
## TS-06 Slow downstream dependency (device-intelligence vendor)

**Symptoms.** 48% of decisions carry `DEVICE_RISK_UNAVAILABLE`; the device-risk circuit opens; latency stays
low (client p99 22 ms).

**Impact.** SEV2/SEV3: decisions made without the device signal, so account-takeover detection is weaker.
The latency SLO is protected by the per-dependency budget and the circuit breaker.

**Hypotheses.** Vendor slowness vs network vs our client (connection pool, DNS).

**Investigation.**
```bash
prom 'sum by (outcome) (increase(integration_calls_total{integration="device-risk"}[1m]))'
prom 'max(integration_circuit_state{integration="device-risk"})'        # 2 = OPEN
prom 'sum by (mode) (increase(risk_decisions_degraded_total[1m]))'
```

**Evidence** ([ts-06](../troubleshooting-lab/evidence/ts-06/)). In one minute: 879 `circuit_open`, 7
`timeout`, 32 `success`; circuit state 2 (OPEN); 1,076 decisions degraded with `DEVICE_RISK_UNAVAILABLE` and
no other mode. The circuit was still OPEN 15 s after the fault was cleared; the load had stopped by then, so
the half-open probe had no traffic to test with. **Recovery was not confirmed in this run.**

**Root cause.** Vendor latency +250 ms against a 60 ms budget (`platform.budgets.device-risk-ms`).

**Remediation.** Nothing to change on our side during the incident (breaker working as designed). Contact the
vendor; if the degraded share persists, consider temporarily raising the ATO rules' weight on other signals.

**Prevention.** Contracted vendor SLOs and status-page subscription; alert `CircuitOpen`; a synthetic probe so
half-open recovery does not depend on real traffic. Stage 8 showed that a slow vendor can hurt more than a
dead one (J-23: abandoned connections and CPU spikes before the circuit opens).

**Customer communication.** "From 10:22 to 10:23 UTC our device-intelligence provider was slow. Scoring
continued within normal latency, but about half of the decisions were made without device risk information.
Transactions in that window can be re-screened on request. The provider has recovered."

**Follow-up.** Re-screening report for the window; confirm half-open recovery with a probe (open item).

---

<a id="ts-07"></a>
## TS-07 Message-consumer lag (case creation)

This incident has two parts: an **unplanned** broker-side fault found on the first attempt, and the planned
scenario.

### TS-07a (unplanned) Consumer group stuck in a rebalance loop

**Symptoms.** The first TS-07 run showed no lag metric and no DLT growth, yet the case-management system had
**0 cases**. 7,380 REVIEW decisions in 10 minutes had produced no case since 17:32.

**Investigation.**
```bash
docker run --rm --network fraud-platform_default apache/kafka:4.1.0 \
  /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --all-groups --state
docker exec $DS jcmd 1 Thread.print          # are listener threads polling or stuck?
docker logs fraud-platform-kafka-1 | grep -E "ERROR|case-creator"
```

**Evidence** ([ts-07-unplanned](../troubleshooting-lab/evidence/ts-07-unplanned/)).
* `case-creator` and `history-ingestor` in state **CompletingRebalance** with 1 member; case-creator lag
  **185,314**, history-ingestor 167,394.
* Listener threads were healthy: `RUNNABLE` in `KafkaConsumer.poll`. The client log showed an endless loop:
  `SyncGroup failed: The coordinator is not available` → re-join → assignment → failure (generation 165+).
* Broker log: `COORDINATOR_NOT_AVAILABLE when storing group assignment` for `__consumer_offsets` partitions
  23 and 48, plus 21 × `Histogram recorded value cannot be negative` and 3 × `The state machine of the
  coordinator __consumer_offsets-23 is out of sync with the underlying log` (first occurrence 15:03).
* The consumer lag metric was **NaN**: a consumer without assigned partitions reports no lag, so a lag
  alert would never have fired.

**Root cause.** Broker-side group-coordinator state for two `__consumer_offsets` partitions fell out of sync
with its log (image `apache/kafka-native:4.1.0`, single broker, on Docker Desktop). The underlying trigger was
**not established**; the negative-histogram errors suggest a clock or timing anomaly, but that is a
hypothesis. Not a defect in this codebase.

**Remediation.** Broker restart (reloads coordinator state from the log). The group became `Stable` within
35 s and drained. No data was lost: offsets were committed per record and consumers are idempotent.

**Recurrence** ([ts-07-recurrence](../troubleshooting-lab/evidence/ts-07-recurrence/)). About two hours later the
same broker errors appeared for `history-ingestor` (`__consumer_offsets-48`). This time the newly added
`ConsumerRebalanceFailures` alert fired (91 failed rebalances in 10 min) and `ConsumerWithoutPartitions` went
pending, so the alerts detected it before any symptom was noticed. The lab broker was switched from
`apache/kafka-native` to the JVM image `apache/kafka:4.1.0` (the broker has no volume in the lab, so topics were
recreated; the outbox table allows re-publishing). Smoke test afterwards: 401 requests → 393 new cases. Whether the
native image is the trigger is still an open observation, not a conclusion.

**Prevention (implemented).** Alerts on the symptom rather than on lag: `CaseCreationStalled` (REVIEW
decisions without new cases for 10 min), `ConsumerWithoutPartitions`, `ConsumerRebalanceFailures`. In
production: a managed, multi-broker Kafka (e.g. Amazon MSK) and a pinned, tested broker image.

### TS-07b (planned) Slow case-management system

**Symptoms.** Case management answers in 3 s (client attempt timeout 1.5 s, deadline 5 s) at 20 rps of
traffic. Lag grows: 352 → 647 → 809 over 90 s.

**Impact.** SEV2: REVIEW decisions do not reach analysts. Payments are not affected (asynchronous flow).

**Investigation.**
```bash
kafka-consumer-groups.sh --describe --group case-creator            # lag, state, members
prom 'sum by (kind) (increase(risk_cases_dispatch_failures_total[2m]))'
sql -c "SELECT status, count(*) FROM fraud_cases WHERE created_at > now()-interval '5 min' GROUP BY 1"
curl "localhost:8080/v1/admin/tenants/aldermoor-bank/events/dlt?topic=fraud.decisions.v1.case-creator.dlt&max=50"
```

**Evidence** ([ts-07](../troubleshooting-lab/evidence/ts-07/)).
* The case-management **circuit breaker opened**: 1,008 `CIRCUIT_OPEN` failures and 1 `TIMEOUT` in 2 min.
  Retries then failed in milliseconds, so records reached the DLT almost immediately: **972 DLT
  publications** in 90 s.
* 969 cases stayed `PENDING_EXTERNAL`.
* After recovery, lag drained in **17 s**; the redrive re-published 920 records. The scheduled reconciliation
  job re-dispatched the remaining 35 `PENDING_EXTERNAL` cases on its next cycle.
* Final state: every REVIEW decision in the window had an `OPEN` case (1,544 + 35); **no case lost**.

**Root cause.** Downstream latency above the client timeout.

**Remediation.** Wait for recovery, then `POST …/events/dlt/redrive`; the reconciliation job covers cases
stuck in `PENDING_EXTERNAL`.

**Related real findings.**
* **J-27, consumer throughput.** While draining the TS-07a backlog, the case-creator processed only **88
  records/s**: one listener thread and one synchronous REST call per record (11.3 ms per record). That is
  below the REVIEW rate at 200 rps of scoring. Fix: listener concurrency 3, one per partition, configurable
  via `platform.messaging.case-creator.concurrency`. Measured drain rate: **241 records/s**.
* **J-31, design observation (not implemented).** Circuit breaker + retry → DLT turns an outage into a manual
  redrive task. Better: when the dependency's breaker is OPEN, pause the listener container (back-pressure),
  so the outage becomes lag that drains itself.

**Customer communication.** "From 10:36 to 10:38 UTC your case-management system responded slowly and new
review cases were not created in real time. No payments were affected and no cases were lost. All 1,579
cases from that window were created after your system recovered (the last ones at 10:39)."

**Follow-up.** Agree timeout and capacity numbers with the customer's case-management team; decide on
pause-on-open (J-31).

---

<a id="ts-08"></a>
## TS-08 Duplicate event processing

**Symptoms.** An operator replays the last 5 minutes of `RiskDecisionCreated` events (the same happens when
the outbox relay crashes between "sent" and "marked sent").

**Impact.** Risk: duplicate cases and duplicate analyst work, or double-counted labels.

**Investigation.**
```bash
curl -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/events/replay \
  -d '{"eventType":"RiskDecisionCreated","from":"…","to":"…"}'
prom 'sum(increase(risk_consumer_duplicates_total[2m]))'
sql -c "SELECT count(*) FROM fraud_cases"; curl localhost:8090/cases/v1/cases   # internal vs external count
```

**Evidence** ([ts-08](../troubleshooting-lab/evidence/ts-08/)). 2,278 events requeued; the case-creator
detected **1,926 duplicates**. The difference is the non-REVIEW events, which the handler ignores before the
duplicate check. `fraud_cases` 290,515 → 290,515 and external cases 181,281 → 181,281: **0 duplicates**.

**Root cause / design.** At-least-once delivery is expected; consumers de-duplicate on `eventId`
(`processed_events`) and the effects are idempotent (one case per decision; the case-management API is
idempotent on `Idempotency-Key` = decision ID).

**Prevention.** Keep the three layers (dedup table, natural idempotency, idempotent downstream API); contract
tests for idempotency with every downstream system.

**Customer communication.** Normally none; if asked: "Events were re-published as part of a recovery
procedure. Our processing is idempotent and no duplicate cases were created (verified: 0 new cases for
2,278 re-published events)."

**Follow-up.** Retention policy for `processed_events` must exceed the maximum replay window (documented).

---

<a id="ts-09"></a>
## TS-09 Malformed input file

**Symptoms.** `FileRejected` alert for `TXN_HISTORY_aldermoor-bank_20260430_902.csv`.

**Impact.** SEV3: batch history for that day is not loaded, so velocity and "seen before" features miss that
activity until the file is fixed.

**Investigation.**
```bash
cat data/runtime/files/reports/<file>.report.json
head data/runtime/files/quarantine/<file>.quarantine.jsonl        # line numbers + raw lines + errors
curl localhost:8081/v1/ingestion/runs?tenant=aldermoor-bank
```

**Evidence** ([ts-09](../troubleshooting-lab/evidence/ts-09/)). 50 records: 10 invalid (**20% > 5%**
threshold) → `REJECTED / TOO_MANY_INVALID_RECORDS`, **0 events published**. Errors: `amount: invalid decimal`
× 8 (raw value `"12,50"`, a decimal comma from a spreadsheet export), `event_time: required` × 2. The report
masks values in the error histogram (to group errors without leaking data); the quarantine file keeps line
numbers and raw lines for the operations team.

**Root cause.** The upstream export changed locale and dropped timestamps (lab: generated deliberately).

**Remediation.** Ask the customer for a corrected file with the same name and a new sequence number; the
duplicate detector ensures already-loaded records are not loaded twice (the 40 valid rows here were
duplicates of the 001 file).

**Prevention.** Error-ratio threshold per file type (reject systemic problems, quarantine isolated ones); the
file contract (name, header, formats) is part of the integration sign-off.

**Customer communication.** "Today's transaction history file (sequence 902) was not loaded: 10 of 50 records
were invalid (amounts with a decimal comma in 8 records, missing timestamps in 2). Please send a corrected file
as sequence 904. Real-time scoring is not affected; features depending on yesterday's batch history are
incomplete until then."

**Follow-up.** Confirm the export job's locale with the customer's core-banking team.

---

<a id="ts-10"></a>
## TS-10 Schema mismatch

**Symptoms.** `FileRejected` alert for sequence 901, reason `SCHEMA_MISMATCH`.

**Evidence** ([ts-10](../troubleshooting-lab/evidence/ts-10/)). The report shows expected and actual header;
`amount` and `currency` are swapped. The whole file was rejected before any record was processed; 0 events.

**Root cause.** Upstream schema change without a new file-spec version (lab: generated deliberately).

**Why reject the whole file.** A column swap parses "successfully" for some rows (a currency code is not a
number, but a number in the currency column would look like garbage, not a schema problem). Strict header
matching turns a silent data-quality issue into an explicit, early failure.

**Remediation / prevention.** Corrected file, or a new spec version if the change is intentional (versioned
file specs, header = contract). Schema changes go through change management with a test file first.

**Customer communication.** "File sequence 901 was not loaded because its columns differ from the agreed
format (`currency` and `amount` are swapped). If this is an intentional change, we can add the new layout as
a new version of the file specification; otherwise please resend with the agreed layout."

---

<a id="ts-11"></a>
## TS-11 Model timeout

Two different model paths: **(A)** the explanation service (FastAPI, SHAP), which analysts call on demand, and
**(B)** in-process ONNX inference on the scoring hot path.

**Evidence** ([ts-11](../troubleshooting-lab/evidence/ts-11/)).
* **A. Model service answering in 6 s** (client deadline 4 s). The explanation endpoint returned **HTTP 200
  after 4.02 s**, with `shapAvailable=false`, `note="model-service unavailable: TIMEOUT"` and the 3 reason
  codes stored with the decision. The analyst still gets an explanation, without SHAP values.
* **B. Inference slowed to 80 ms** (budget 25 ms), 30 rps: **100% of decisions degraded with
  `MODEL_UNAVAILABLE`** (923 in the window); latency stayed bounded (client p99 40.6 ms, server p99
  31.9 ms). The fallback policy decided **499 REVIEW and 402 APPROVE (55% REVIEW)**.

**Impact.** A: SEV3 (analyst waits 4 s, less detail). B: SEV2 — scoring continues, but the fallback floods
the review queue. That is the intended safe side for fraud, and it is an operational problem for the
customer's analysts.

**Investigation.**
```bash
prom 'sum by (reason) (increase(risk_model_failures_total[1m]))'
prom 'histogram_quantile(0.99, sum by (le) (rate(risk_model_inference_seconds_bucket[1m])))'
prom 'sum by (decision) (increase(risk_decisions_total[1m]))'
```

**Root cause.** A: model-service latency. B: inference slower than its budget (lab fault; in reality: CPU
contention, a larger model, thread-pool starvation).

**Remediation.** A: none needed immediately. B: restore capacity or roll back the model; tell the customer's
fraud operations team to expect a review surge.

**Prevention.** Model latency is part of the promotion gate (golden-score timing); inference thread pool sized
and isolated; the explanation deadline for interactive use could be lower than 4 s (open item). Fallback
policy per channel agreed with the customer in advance (documented in the strategy).

**Customer communication (B).** "From 10:23 to 10:24 UTC our model could not score within its time budget.
Decisions continued within normal latency using the agreed fallback policy, which sends more transactions to
review: 55% of decisions in that window were REVIEW. Your team may see a temporary increase in the review
queue. Model scoring has been restored."

**Follow-up.** Agree fallback-policy expectations (review capacity) with the customer; lower the interactive
explanation deadline.

---

<a id="ts-12"></a>
## TS-12 Redis unavailable

**Symptoms.** Redis stopped for 40 s at 50 rps.

**Evidence, part 1: the resilience design worked** ([ts-12](../troubleshooting-lab/evidence/ts-12/)). Both
Redis breakers opened (`redis-features`, `redis-graph`). In a 20 s window, 1,005 decisions were degraded
(`FEATURE_STORE_DEGRADED` + `GRAPH_FEATURES_UNAVAILABLE`), the bounded PostgreSQL fallback served 1,004
reads and rejected 0, and Hikari had 0 pending. No request failed.

**Evidence, part 2: recovery failed (first run).** After Redis returned, `POST
…/feature-store/rebuild` and `…/graph/reload` both returned **HTTP 500**. Stack trace (via the correlation
ID): `java.net.BindException: Cannot assign requested address` connecting to `redis:6379`.

```bash
# inside the container: TCP states and remote ports
docker exec $DS sh -c 'cat /proc/net/tcp /proc/net/tcp6 | awk "NR>1{print \$4}" | sort | uniq -c'   # 06 = TIME_WAIT
docker exec $DS cat /proc/sys/net/ipv4/ip_local_port_range
```
24,890 sockets in TIME_WAIT, 23,950 of them to port 6379, out of an ephemeral range of 28,232 ports.

**Root cause (real defect, J-26).** `RedisTemplate.executePipelined` on Lettuce cannot use the shared native
connection. **Without a connection pool, Spring Data Redis opened and closed a dedicated TCP connection for
every pipeline**: 3 per decision (feature read, graph read, feature write). Measured: 4,001 requests → 12,003
TIME_WAIT sockets. With a 60 s TIME_WAIT, the service runs out of ephemeral ports at ~157 rps sustained.

**Fix.** `commons-pool2` + `spring.data.redis.lettuce.pool.*` (16 connections, 20 ms max wait). Verification
([redis-churn](../troubleshooting-lab/evidence/redis-churn/)):

| 200 rps × 120 s (warm) | pool disabled | pool enabled |
|---|---|---|
| Peak TIME_WAIT to Redis | **28,229** (whole port range) | **0** |
| Failed requests | **11.8%** | 0% |
| Degraded decisions | **49.4%** | 0.05% |
| Client p50 / p95 / p99 | 54.7 / 290.9 / 384.4 ms | 9.5 / 14.6 / 37.8 ms |

At 100 rps (below the ceiling) latency was within run-to-run noise (p99 19.7 ms before, 16.8 and 19.0 ms in
two runs after); the fix removes a capacity ceiling, it is not a latency optimisation.

**Evidence, part 3: an assumption that was wrong (J-30).** The script assumed Redis would come back empty
(`--appendonly no`). The Redis log showed `Saving the final RDB snapshot before exiting` and `keys loaded:
31386`: AOF was off, the default RDB snapshots were not. What was actually lost were the **3,373 feature
writes skipped during the outage**. The rebuild replays them from PostgreSQL (316,607 transactions, 30 days,
**54.8 s**).

**Remediation.** Restore Redis → rebuild the feature store for the affected window → reload graph snapshots.
```bash
curl -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/feature-store/rebuild?days=30" -H "X-Api-Key: …"
curl -X POST  localhost:8080/v1/admin/tenants/aldermoor-bank/graph/reload -H "X-Api-Key: …"
```

**Prevention.** Managed Redis with replica and automatic failover (ElastiCache Multi-AZ); alert
`FeatureStoreWritesSkipped` (added) → run the rebuild after recovery; decide explicitly on persistence (RDB
interval vs rebuild time); the pool fix and a load test above 157 rps in the release pipeline.

**Customer communication.** "From 10:54 to 10:55 UTC our feature cache was unavailable. Scoring continued
normally using a reduced feature set; no requests failed. Features for transactions processed in that minute
were rebuilt afterwards."

**Follow-up.** Re-run the Stage 8 capacity tests with the fix (see TS-15); add a >157 rps soak test.

---

<a id="ts-13"></a>
## TS-13 False-positive (decline) spike after a strategy change

**Scenario.** Quillon Pay's superseded strategy 1.0.0 (its decline threshold caused **183 false declines in
18 days** in the offline evaluation, J-05) is re-activated in dev at 100% with the reason "restore previous
behaviour".

**Evidence** ([ts-13](../troubleshooting-lab/evidence/ts-13/)).

| Window (40 rps, Quillon) | Strategy | APPROVE | REVIEW | DECLINE |
|---|---|---|---|---|
| Baseline | 1.1.0 | 98.7% | 1.0% | 0.3% |
| After promotion | 1.0.0 | 99.1% | 0.5% | 0.4% |
| After rollback | 1.1.0 | 94.6% | 5.3% | 0.1% |

**The live effect was small, and the before/after comparison was confounded.** The same configuration
(1.1.0) showed 1.0% REVIEW before and 5.3% after. Each window scores different transactions and adds velocity
state. The controlled comparison is the **paired simulation**: the same 5,000 recent transactions scored by
both versions (`POST …/strategies/1.0.0/simulate`):

* decline rate 0.12% → **0.34% (2.8×)**; review rate 5.62% → 0.84%;
* 235 REVIEW → APPROVE and 11 → DECLINE transitions.

**Investigation.** Decision mix by `strategy_version` in the same window; audit trail
(`STRATEGY_ACTIVATED` by `quillon-admin` at 18:30:41); simulation of the suspect version against the active
one.

**Remediation.** `POST …/deployments/dev/rollback` (If-Match row version), audited as `STRATEGY_ROLLBACK`
(18:31:43).

**Prevention.** Promotion requires an attached simulation report; staged rollout (canary %) with automatic
comparison of cohorts **in the same window**; a declined-genuine feedback loop (chargebacks / customer
complaints) with a short lag.

**Customer communication (SEV1 in a real decline spike).** "Between 10:30 and 10:31 UTC an earlier strategy
version was re-activated in your development environment. It declines more genuine transactions (in our
simulation on 5,000 recent transactions, the decline rate rose from 0.12% to 0.34%). It was rolled back after
one minute. We will provide the list of affected transactions."

**Follow-up.** Make the simulation report a hard requirement in the promotion API (currently a process step).

---

<a id="ts-14"></a>
## TS-14 Review-queue spike (threshold typo in a canary)

**Scenario.** Aldermoor strategy 1.2.0 is meant to tighten the review threshold to 0.35 but ships **0.04**.
It passes schema validation (0 warnings) and four-eyes approval (the author's self-approval was rejected
with HTTP 403; the second person approved), and goes out as a **25% canary**.

**Evidence** ([ts-14](../troubleshooting-lab/evidence/ts-14/)).

| Same window, 60 rps | APPROVE | REVIEW | DECLINE |
|---|---|---|---|
| Control 1.1.0 (75% of customers) | 25.6% | **74.2%** | 0.2% |
| Canary 1.2.0 (25% of customers) | 10.6% | **89.0%** | 0.4% |

2,795 cases opened in the canary minute. Paired simulation (5,000 transactions): REVIEW 77.1% → 90.6%.
Rollback at 18:34:26 removed the candidate; the audit trail shows draft (admin) → approval (approver) →
canary → rollback.

**The baseline itself was wrong (J-28).** 74% REVIEW for the control is not a realistic mix. The top REVIEW
reasons were `HIGH_TRANSACTION_VELOCITY` (6,249) and `ANOMALOUS_PATTERN` (3,540), with a mean model
probability of 0.007. The k6 pool holds 5,000 transactions over 2,679 customers; at 100 rps each customer
makes ~130 transactions per hour with `eventTime=now`. Over the day the REVIEW share rose from 47% to 88% as
the 24 h velocity window filled. **The relative effect (+14.8 pp in the canary, same window) and the
blast-radius control are valid findings; the absolute rates are a load-test artifact.**

**Remediation.** Rollback; fix the threshold; re-simulate; re-approve.

**Prevention.** Validation warnings for large threshold changes relative to the active version (e.g. > 50%
relative); promotion gated on the simulation report; canary auto-abort when the cohort's REVIEW share differs
from control beyond a set tolerance; alert `ReviewShareDrift` (added).

**Customer communication.** "At 10:33 UTC a strategy update with an incorrect review threshold was released
to 25% of your customers. For one minute, transactions from those customers were sent to review much more
often, creating about 2,800 additional review cases. The update was rolled back. The affected cases can be
bulk-closed; we can provide the list."

**Follow-up.** Implement the relative-threshold-change warning; bulk-close tooling for cases created by a
rolled-back strategy (open item).

---

<a id="ts-15"></a>
## TS-15 Latency regression after deployment (cold JVM)

**Symptoms.** Right after a deployment, p95/p99 latency, degraded decisions and 503 OVERLOADED rise; they
fade over minutes without any intervention. The same build passes the SLO when warm.

**Impact.** SEV2 during every rolling deployment or scale-up if traffic hits new pods at full rate.

**Hypotheses.** (1) Code regression in the new version. (2) JIT compilation on a cold JVM. (3) Cold caches /
connections (J-13). (4) An environment change deployed together with the code.

**Investigation.** Compare the same build cold vs warm under the same load; JFR thread CPU load shows where
CPU goes.
```bash
docker compose -f deploy/docker-compose.yml restart decision-service && perf/run.sh baseline   # cold
perf/run.sh baseline                                                                          # warm, same build
docker exec $DS jfr view thread-cpu-load /dumps/baseline.jfr
```

**Evidence** (same build, after the J-26 fix; 60 s warm-up at 50 rps + 180 s at 150 rps; JFR at t=100 s):

| Run | JVM | Sustained p95 / p99 | Failed | Degraded | Hikari pending max | CPU peak | C2 compiler thread CPU |
|---|---|---|---|---|---|---|---|
| [baseline-09](../perf/results/baseline-09-cold-redis-pool/) | restarted just before | **327 / 534 ms** | 4.04% | 20.3% | 21 | 100% | **20.6%** (top thread) |
| [baseline-07](../perf/results/baseline-07-warmup-redis-pool/) | semi-cold | 346 / 628 ms | 4.86% | 27.7% | 9 | 100% | – |
| [baseline-08](../perf/results/baseline-08-redis-pool-warm/) | warmed by a full run | **15.2 / 40.1 ms** | 0.017% | 3.4% | 0 | 64% | not in top threads |

Stage 8 showed the same pattern before the fix (baseline-05 cold: p95 351 ms; baseline-06 warm: 18.4 ms;
C2 at 24–30% of CPU, J-22).

**Root cause.** The C2 JIT compiler competes with request handling for the 2 vCPU during the first minutes;
full-rate traffic on a cold JVM saturates the CPU, and budget timeouts turn into degraded decisions and shed
load.

**Remediation.** During the incident: shift traffic back to warm pods (slow down or pause the rollout), scale
out. Not a code rollback — the build is fine.

**Prevention.** Slow start for new pods (load-balancer slow-start / gradual weight), HPA scaling before
saturation (60% target, see AWS_AND_KUBERNETES.md), warm-up traffic before readiness (the existing
`WarmUpRunner` only covers models and connections, not JIT), and JIT/AOT caching (Leyden AOT cache in JDK
24+, CRaC) as a future improvement. Performance tests must state JVM warmth; comparisons only at equal warmth
(J-19).

**Customer communication.** "During today's deployment window (10:05–10:09 UTC) scoring latency was elevated
(95th percentile about 330 ms against a 100 ms target) and about 4% of requests were rejected with a retry
signal while new instances warmed up. We are changing the rollout so new instances receive traffic
gradually."

**Follow-up.** Implement slow start in the ingress / service mesh; evaluate an AOT cache on the target JDK.

---

<a id="ts-16"></a>
## TS-16 Failed deployment

**Scenario.** A new decision-service instance starts next to the running one, as in a rolling update, with
(A) a rotated database secret that was not updated in the deployment and (B) a wrong models path.

**Evidence** ([ts-16](../troubleshooting-lab/evidence/ts-16/), pre-fix run in
[ts-16-before-fix](../troubleshooting-lab/evidence/ts-16-before-fix/)).
* **A.** The container exited with code 1 after ~35 s: `Unable to obtain connection from database: FATAL:
  password authentication failed`. In Kubernetes this is CrashLoopBackOff; the rollout stalls and the old
  pods keep serving. The running instance answered HTTP 200 throughout.
* **B, before the fix: the instance reported READY (readiness 200)** although every model failed to load
  (`NoSuchFileException: /app/models-v2/…/manifest.json`, `allModelsLoaded:false`). A rolling update would
  have replaced healthy pods with pods that decide 100% in fallback mode (see TS-11: 55% REVIEW).
* **B, after the fix: readiness 503, liveness 200.** Kept out of traffic, not restart-looped. The fixed
  instance (correct secret and path) became ready in ~13 s.

**Root cause (real defect, J-29).** The `models` health indicator deliberately reported UP when models
failed to load ("serve in fallback mode"). That reasoning holds for runtime inference failures, not for a
startup load failure: models are loaded once, so such an instance is strictly worse than its peers.

**Fix.** Readiness is DOWN when the active model is not loaded; `platform.readiness.require-models=false` is
an explicit opt-out for a deliberate rules-only operation. Unit test `ModelsReadinessTest`.

**Investigation commands.**
```bash
kubectl rollout status deploy/decision-service; kubectl get pods; kubectl describe pod <new>
kubectl logs <new> --previous                     # crash reason
curl http://<pod>:8080/actuator/health/readiness  # which component is DOWN
```

**Prevention.** Secrets from a secret manager with rotation hooks; readiness covers everything the instance
needs to decide correctly; `maxUnavailable: 0` so a stalled rollout never reduces capacity; a smoke test
(golden transactions) after rollout.

**Customer communication.** Usually none: a stalled rollout has no customer impact. For the change record:
"The planned deployment was halted automatically because the new version did not pass its readiness checks
(configuration error). The current version continued serving. A corrected deployment is scheduled for …"

**Follow-up.** Pre-deployment config validation (secret exists and authenticates, model artifacts present).

---

<a id="ts-17"></a>
## TS-17 Broken database migration

**Scenario** (scratch database `riskplatform_ts17`, V1–V5 plus 20,000 copied decisions; the real schema is
never touched). The release ships `V6__decision_channel.sql`: `ALTER TABLE risk_decisions ADD COLUMN channel
text NOT NULL;`, written against an **empty** developer database.

**Evidence** ([ts-17](../troubleshooting-lab/evidence/ts-17/), migrations in
[`troubleshooting-lab/ts-17-migrations/`](../troubleshooting-lab/ts-17-migrations/)).
* `flyway migrate` exit 1: `SQL State 23502 — column "channel" of relation "risk_decisions" contains null
  values`, `Changes successfully rolled back`. `flyway info` shows V6 **Pending**; the column does not exist.
  PostgreSQL DDL is transactional, so there is nothing to repair. With Spring Boot running Flyway at startup,
  the new pods would fail to start (same pattern as TS-16 A) while the old pods keep serving.
* **Fix: expand → backfill → contract.**
  1. V6 expand: nullable column (metadata-only, 9 ms).
  2. Batched backfill job: 4 × 5,000 rows, ~1 s, restartable, short transactions.
  3. V7 contract: `CHECK (channel IS NOT NULL) NOT VALID` → `VALIDATE CONSTRAINT` → `SET NOT NULL` (reuses
     the validated constraint, PostgreSQL 12+) → drop the helper constraint.
  Result: `channel NOT NULL`, all 20,000 rows filled (ECOM 8,019, POS 6,991, MOBILE 3,161, …).

**Root cause.** A migration tested on an empty database; contract change shipped in the same release as the
expand.

**Remediation.** The failed release was never applied anywhere, so V6 could be replaced. If a migration has
already run in any environment, never edit it — add a new one.

**Prevention.** Run migrations in CI against a production-sized, anonymised snapshot; lint migrations
(`NOT NULL` without default, non-concurrent index, column renames); expand/contract across two releases so
version N and N+1 both work with the schema; `lock_timeout` for DDL.

**Customer communication.** Only if a maintenance window was announced: "The database change planned for
tonight was not applied (it failed a safety check and was rolled back automatically). No data was affected.
A revised change is scheduled for …"

**Follow-up.** Add the migration lint and the snapshot migration test to the pipeline (see
MIGRATION_AND_UPGRADE_RUNBOOK.md, which applies the expand/contract fix for real in release 2.0).

---

<a id="ts-18"></a>
## TS-18 Data inconsistency (feature-store state lost)

**Scenario.** Redis loses its data (`FLUSHALL`: eviction storm, failover to an empty replica, operator error).
Redis stays up and answers quickly.

**Evidence** ([ts-18](../troubleshooting-lab/evidence/ts-18/)). Three 40 s windows at 40 rps:

| Phase | REVIEW | avg `account_txn_count_24h` | graph risk | `NEW_DEVICE` reason | velocity reason | degraded |
|---|---|---|---|---|---|---|
| Baseline | 63.6% | 201.1 | 0.0021 | 0.0% | 100% | 0% |
| After FLUSHALL | **1.6%** | **0.4** | **0.0000** | 2.6% | 0.4% | **0%** |
| After rebuild | 82.9% | 199.9 | 0.0033 | 0.0% | 100% | 0% |

No circuit breaker opened, no decision was marked degraded, and **no existing alert fired**. Detection
collapsed silently: velocity and graph signals disappeared and "new device" fired for known devices. (The
high baseline REVIEW share is the load-test artifact from TS-14; the *change* is the finding.)

**Root cause.** Loss of derived state; the system cannot tell "no history" from "lost history".

**Remediation.** Rebuild the feature store from PostgreSQL (54.8–55.9 s for 30 days of Aldermoor history) and
reload graph snapshots.

**Prevention (real gap, J-32).**
* Implemented: alert `ReviewShareDrift`, which fires when the REVIEW share over 15 min halves or doubles
  against the previous 3 h.
* Open items: feature-distribution monitoring (PSI on key features against a reference window); a canary key
  in Redis whose absence triggers a rebuild; `maxmemory-policy` that never evicts feature keys (currently
  `volatile-lru` with TTLs).

**Customer communication.** "Between 10:55 and 10:56 UTC our feature cache lost its history. Scoring
continued without errors, but decisions in that window used incomplete behavioural features and were
therefore less strict (fewer reviews). The cache was rebuilt from the transaction history. We recommend
re-screening the 1,601 transactions from that window; the list is attached."

**Follow-up.** Re-screening job for a time window (open item); PSI monitoring.

---

## What the lab found that was not planned

| # | Finding | Found in | Status |
|---|---|---|---|
| J-25 | Kafka broker coordinator out of sync → consumer groups in a rebalance loop; lag metric NaN | TS-07a | Mitigated (restart); symptom alerts added; trigger not established |
| J-26 | Redis pipelines opened a TCP connection each → ephemeral-port exhaustion at ~157 rps | TS-12 | **Fixed** (Lettuce pool), A/B verified |
| J-27 | Case-creator throughput 88 records/s (single thread) | TS-07a drain | **Fixed** (concurrency 3 → 241/s) |
| J-28 | Load-test pool inflates velocity → unrealistic decision mix; before/after comparisons confounded | TS-13/14 | Documented; paired simulation / same-window cohorts |
| J-29 | Instance without models reported ready | TS-16 | **Fixed** + unit test |
| J-30 | Assumed Redis restarts empty; RDB snapshots were active | TS-12 | Corrected in the evidence |
| J-31 | Breaker OPEN + retries → mass DLT instead of back-pressure | TS-07b | Documented, not implemented |
| J-32 | Feature-store state loss produces no alert | TS-18 | Alert added; PSI monitoring open |

## Tool reference

| Question | Tool |
|---|---|
| What are request threads doing? (virtual threads) | `jcmd 1 Thread.dump_to_file -format=json <file>` |
| Carrier / platform threads, deadlocks | `jcmd 1 Thread.print` |
| Where does CPU go? Lock contention? Pinning? | `jcmd 1 JFR.start duration=30s settings=profile` → `jfr view hot-methods`, `jfr print --events jdk.JavaMonitorEnter,jdk.VirtualThreadPinned` |
| Heap | `jcmd 1 GC.heap_info`, `GC.class_histogram`, heap dump on OOM → Eclipse MAT |
| Slow SQL | `pg_stat_statements`, `EXPLAIN (ANALYZE, BUFFERS)`, `pg_stat_activity` |
| Kafka consumers | `kafka-consumer-groups.sh --describe --group <g> [--state]` (state, members, lag) |
| DLT | `GET/POST /v1/admin/tenants/{t}/events/dlt[/redrive]` |
| Sockets inside a container without `ss` | `cat /proc/net/tcp /proc/net/tcp6` (column 4 = state, `06` TIME_WAIT, `01` ESTABLISHED) |
| Decision mix by strategy version | `SELECT strategy_version, decision, count(*) FROM risk_decisions WHERE created_at > … GROUP BY 1,2` |
| Paired strategy comparison | `POST /v1/admin/tenants/{t}/strategies/{v}/simulate?limit=5000` |

Pitfalls met in this lab: Git Bash rewrites container paths (`MSYS_NO_PATHCONV=1`); output filters truncated
JSON (write responses to files); `evidence()` resets an incident folder, so keep cross-incident measurements
in their own folder (the Redis churn "before" file had to be transcribed from console output after it was
deleted, and is marked as such).
