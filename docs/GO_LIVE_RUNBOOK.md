# Go-Live Runbook — Aldermoor Bank

> **Template for a fictional go-live.** It uses only capabilities that exist in this repository (strategy
> canary percentages, rollback API, emergency rules, readiness gates, alerts). Where a step depends on the
> customer's side (gateway routing), that is stated. Not executed with a real customer.

## 1. Go-live strategy: staged, reversible, measured

| Stage | What changes | Exposure | Exit criteria | Rollback |
|---|---|---|---|---|
| S0 Parallel run (2 weeks) | Gateway calls the scoring API for every transaction but **does not act on the decision** (customer gateway setting). The platform records every decision | 0% effect, 100% traffic | Latency SLO met at real peak; decision mix vs review capacity; comparison with the legacy rule outcomes signed off by fraud ops | Gateway stops calling |
| S1 Low-risk channel | Enforce decisions for **POS card payments** | ~30% of volume (assumption) | 3 days: SLOs green, review volume ≤ plan, no unexplained declines | Gateway reverts POS to legacy rules |
| S2 Canary on the next channel | E-commerce: enforce; strategy candidate at **10% → 50%** via `rolloutPercentage` | 10–50% of e-commerce customers | Canary cohort vs control in the same window (TS-14 lesson): REVIEW share, declines, complaints | `POST …/deployments/prod/rollback` (< 1 min, audited) |
| S3 All card traffic | 100% | Cards | 5 days stable | as above |
| S4 Instant transfers | A2A transfers; fail policy REVIEW on timeout | Transfers | 5 days stable; scam-typology alerts reviewed daily | Gateway reverts transfers to legacy |
| Hypercare | Daily KPI review, tuning | – | 2 weeks stable → handover | – |

**Platform gap (future improvement):** a platform-side "monitor-only" switch per tenant/channel (score and
record, always return the legacy-compatible answer) would remove the dependency on the gateway for S0. Not
implemented; today S0 relies on the customer's gateway ignoring the response.

## 2. Readiness (go / no-go for S0 and S1)

Complete [customer/production-readiness-checklist.md](customer/production-readiness-checklist.md). Hard
criteria:

| # | Criterion | Evidence |
|---|---|---|
| G1 | Performance test in the customer's staging: p99 ≤ 250 ms at 1.5 × expected peak, **with warm and cold JVM results** | perf report (repeat of `perf/run.sh` in staging) |
| G2 | Resilience tests passed in staging: Redis loss, vendor slow, DB failover, bad deployment | TS-06, TS-12, TS-16 procedures |
| G3 | Alerts routed to the on-call rota and tested (fire each critical alert once) | alert test log |
| G4 | Rollback rehearsed: strategy rollback < 5 min; application rollback; migration rollback | TS-13/14, MIGRATION runbook §8 |
| G5 | Fail policies per channel signed by fraud ops and risk (e.g. POS fail-open ≤ €250, transfers → REVIEW) | strategy `channelPolicies` |
| G6 | Review capacity plan: expected REVIEW volume per day vs analysts, per channel | parallel-run report |
| G7 | Emergency rule procedure tested (block a device/BIN in minutes, audited) | `POST …/strategies/{v}/derive` with `emergency: true` |
| G8 | Data flows reconciled for 5 consecutive days (files, labels, settlement) | reconciliation report |
| G9 | Security: credentials in the secret manager, API clients least privilege, audit export works | security checklist |
| G10 | Support model: L1 customer / L2 vendor, contacts, severity definitions, escalation path | handover draft |

Any "no" on G1–G5 is a no-go. G6–G10 may have agreed mitigations.

## 3. Cutover day plan (S1 example)

| Time (T) | Step | Owner | Check |
|---|---|---|---|
| T-24h | Change approved (CAB); communication sent ([template](customer/go-live-communication.md)) | Implementation lead | ticket |
| T-2h | Bridge open; dashboards on screen; freeze on other changes | All | – |
| T-1h | Pre-checks: all pods ready, alerts green, strategy versions as expected, **pods warm** (warm-up traffic or slow start; TS-15) | Implementation engineer | `GET /deployments`, Grafana |
| T-0 | Gateway enables enforcement for POS | Customer channels team | first decisions visible |
| T+15m | Check 1: latency, errors, decision mix, degraded modes, admission rejections | Implementation engineer | dashboard |
| T+1h | Check 2 + case creation flowing (REVIEW → case), outbox age ~0 | Implementation engineer + fraud ops | `CaseCreationStalled` silent |
| T+4h | Go / hold decision for the rest of the day | Fraud ops lead | – |
| T+24h | Day-1 review: KPIs vs plan; tuning list | All | status note |

**Rollback triggers (any one):** p99 > 250 ms for 10 min; 5xx > 0.5%; REVIEW volume > 150% of plan for 1 h;
declines of genuine customers confirmed by complaints (> agreed threshold); any data-loss signal (outbox age
growing, consumers without partitions). Rollback owner: implementation lead, no further approval needed.

## 4. Hypercare (2 weeks)

* Daily 30-minute KPI review with fraud ops: detection, false declines (complaints, customer call-backs), review
  volume and age, top reason codes, latency, incidents.
* Tuning changes go through the normal strategy lifecycle (draft → approval → canary → 100%), never ad hoc.
* Every incident gets an update within the severity cadence ([customer/incident-update.md](customer/incident-update.md))
  and a short review.
* Exit: 10 business days without SEV1/SEV2 and KPIs in the agreed range → handover.

## 5. What can go wrong on the day (from the lab)

| Risk | Early signal | Prepared response |
|---|---|---|
| Cold pods after a deployment (TS-15) | p95 spike right after rollout | Slow start / warm-up; pause rollout |
| Review flood from a threshold mistake (TS-14) | `ReviewShareDrift`, case queue growth | Rollback; bulk-close list |
| Case creation stops while everything looks "up" (TS-07a) | `CaseCreationStalled`, `ConsumerWithoutPartitions` | Broker/consumer runbook |
| Feature-state loss → silent under-detection (TS-18) | `ReviewShareDrift` (halving) | Rebuild feature store + graph |
| Vendor slow (TS-06) | `CircuitOpen`, degraded share | Vendor escalation; accept degraded |
| Bad config in a new release (TS-16) | Rollout stalls on readiness | `kubectl rollout undo` |
