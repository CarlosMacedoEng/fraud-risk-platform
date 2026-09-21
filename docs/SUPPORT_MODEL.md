# Post-Production Support Model

> Phase 7 of the implementation plan (fictional engagement). Complements the troubleshooting playbook
> (how to fix) with how support is organised, measured and improved. The known-issues register and the backlog
> are real: they list limitations of this repository.

## 1. Incident severity model

| Sev | Definition | Examples (lab incidents) | Response | Customer updates | Review |
|---|---|---|---|---|---|
| SEV1 | Scoring unavailable, or wrong decisions at scale (mass declines/approvals), or data loss | TS-04 OOM with a single instance; a decline spike in prod (TS-13 pattern) | 15 min, 24×7 | every 30 min | written, ≤ 5 days |
| SEV2 | Degraded scoring (SLO breach, fallback decisions), a data flow stopped, review flood | TS-02, TS-03, TS-07, TS-11 B, TS-14, TS-15, TS-18 | 30 min | every 60 min | written, ≤ 10 days |
| SEV3 | Single integration or batch affected with workaround; analyst tooling degraded | TS-01, TS-06, TS-09, TS-10, TS-11 A | same business day | at changes | in weekly report |
| SEV4 | Question, cosmetic issue, request | – | 2 business days | – | – |

## 2. Support runbook (L1 procedures)

| Situation | First action | Procedure |
|---|---|---|
| Any alert | Open the dashboard linked in the alert; follow "the first 15 minutes" | [TROUBLESHOOTING_PLAYBOOK.md](TROUBLESHOOTING_PLAYBOOK.md) |
| Decision-mix drift | Check recent strategy changes (`GET /deployments`, audit); if one coincides → rollback | TS-13/14 |
| Feature store lost / Redis failover | Rebuild feature store + reload graph | TS-12/18 |
| DLT not empty | Peek, identify cause; redrive after the cause is fixed | TS-07 |
| File rejected | Read the report; request a corrected file from the sender | TS-09/10 |
| Deployment stalled | Check readiness body of the new pod; `rollout undo` | TS-16 |
| Escalate to L2 when | no runbook matches, a runbook step fails, SEV1, or a second occurrence within 7 days | – |

Diagnostic bundle attached to every L2 escalation: time window, tenant, correlation IDs, alert names, dashboard
screenshots, JSON thread dump + JFR (Java issues), `pg_stat_activity` snapshot (DB issues), consumer-group
state (messaging issues).

## 3. Known-issues register

| ID | Issue | Impact | Workaround | Status |
|---|---|---|---|---|
| KI-01 | Cold JVM fails the latency SLO for minutes after (re)start (TS-15) | p95 ~330 ms at 150 TPS right after a deployment | Deploy in low traffic; slow start; warm-up traffic | Open (slow start = platform change) |
| KI-02 | Circuit breaker OPEN + retries dead-letter records instead of back-pressure (J-31) | Manual DLT redrive after an outage of the case system | Redrive runbook | Open |
| KI-03 | No platform-side monitor-only mode | Parallel runs depend on the gateway | Gateway ignores response | Open |
| KI-04 | Feature-state loss detected only through decision-mix drift (J-32) | Up to 25 min of weaker decisions before `ReviewShareDrift` fires (15 min window + 10 min `for`) | Rebuild runbook | Partially mitigated |
| KI-05 | Backfill throttle is static (migration §6) | Operator must tune it and watch the effect | Safe values from rehearsal | Open |
| KI-06 | Lab Kafka broker (native image) coordinator desync (J-25) | Consumers stall | Restart broker; alerts in place | Lab only; production uses managed Kafka |
| KI-07 | Explanation deadline 4 s for interactive use (TS-11 A) | Analyst waits 4 s when model-service is slow | – | Open |

## 4. Operational review (monthly template)

1. **Service levels:** availability, p95/p99, error rate, degraded-decision share, vs SLO; error budget left.
2. **Business KPIs:** detection, false declines (complaints), review volume and age, top reason codes, fail-open
   decisions (count and value, DDR-003).
3. **Incidents:** list, severity, time to detect / mitigate, repeat incidents, actions status.
4. **Changes:** strategy versions, model changes, releases; any rollback and why.
5. **Capacity:** peak TPS vs tested capacity, CPU headroom, DB growth, Kafka lag trends.
6. **Known issues and backlog:** what moved, what is next.
7. **Customer feedback** and requests.

## 5. Feedback loop to product engineering

| Source | Captured as | Example from this project |
|---|---|---|
| Incident with a product cause | Defect ticket with evidence bundle | J-26 (Redis connection churn), J-29 (readiness without models) |
| Repeated manual work | Product improvement request with frequency and cost | DLT redrive after every case-system outage (J-31) |
| Customer workaround | Feature request with the business reason | Monitor-only mode (KI-03) |
| Documentation gap | Docs ticket | Kubernetes service-link pitfall (J-34) |

Each request states: problem, evidence, affected customers, workaround, proposed change, and how to verify it.
Status is reviewed in the monthly operational review so the customer sees that feedback leads somewhere.

## 6. Continuous-improvement backlog (prioritised)

| # | Item | Why (evidence) | Effort |
|---|---|---|---|
| 1 | Slow start / warm-up traffic before readiness | KI-01, TS-15 | M |
| 2 | Pause listener while downstream breaker is OPEN | KI-02, J-31 | M |
| 3 | PSI / feature-distribution monitoring + canary key in Redis | KI-04, TS-18 | M |
| 4 | Adaptive backfill throttle (p99, commit latency, replication lag) | KI-05 | M |
| 5 | Platform monitor-only mode per tenant/channel | KI-03 | S |
| 6 | Re-measure the stress-test knee after J-26 | perf README correction | S |
| 7 | Separate deployment / concurrency limits for async consumers | J-33 | S |
| 8 | Pre-deployment migration Job (migrate-only mode) | AWS_AND_KUBERNETES.md | M |
| 9 | Partitioning + retention for `risk_decisions` | DATABASE_DESIGN.md §8 | L |
| 10 | Terraform/CDK for the AWS design | AWS_AND_KUBERNETES.md §5 | L |
