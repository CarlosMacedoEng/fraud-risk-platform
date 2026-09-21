# Production Readiness Checklist

> Go / no-go checklist (fictional engagement). The "Evidence in this repo" column shows where each item was
> practised locally; in a real project the evidence must come from the customer's staging/production.

| # | Area | Item | Evidence in this repo | Status |
|---|---|---|---|---|
| 1 | Functional | Decisions + reason codes for every channel in scope | API tests, OpenAPI contract test | ☐ |
| 2 | Functional | Strategy lifecycle: validate, approve (four-eyes), canary, rollback | governance tests, TS-13/14 | ☐ |
| 3 | Functional | REVIEW → case in the customer's case system | messaging tests, TS-07 | ☐ |
| 4 | Data | Files ingested, rejected files alerted, reconciliation 5 days clean | TS-09/10, FILE_INTEGRATIONS.md | ☐ |
| 5 | Data | Labels flowing back (chargebacks, analyst outcomes) | messaging tests | ☐ |
| 6 | Performance | p99 ≤ 250 ms at 1.5× peak in staging, warm **and** cold | perf/README.md, TS-15 | ☐ |
| 7 | Performance | Soak test 8 h without memory growth | not done locally (open) | ☐ |
| 8 | Resilience | Redis loss, vendor slow, DB failover, broker loss, bad deployment rehearsed | TS-06, TS-12, TS-16, TS-07a | ☐ |
| 9 | Resilience | No data loss under failure (outbox reconciliation) | degradation test: 34,768 = 34,768 | ☐ |
| 10 | Operations | Dashboards; alerts routed and each critical alert fired once in test | 17 rules, promtool | ☐ |
| 11 | Operations | Runbooks: playbook, migration, go-live, rollback | docs | ☐ |
| 12 | Operations | On-call rota, severity definitions, escalation contacts | handover.md | ☐ |
| 13 | Security | Secrets in secret manager; least-privilege clients; audit export | AWS_AND_KUBERNETES.md (design) | ☐ |
| 14 | Security | Penetration test / security review sign-off | not done (open) | ☐ |
| 15 | Deployment | Rolling update with readiness gates; rollback rehearsed | kind: bad rollout stalled + undo | ☐ |
| 16 | Deployment | Schema migration plan and rollback | MIGRATION runbook | ☐ |
| 17 | Business | Fail policies per channel signed | DDR-003 example | ☐ |
| 18 | Business | Review capacity plan signed | status report D-7 | ☐ |
| 19 | People | Analysts and operators trained | training-agenda.md | ☐ |
| 20 | Communication | Go-live communication sent; bridge scheduled | go-live-communication.md | ☐ |

**Go** requires 1–3, 6, 8–10, 12, 15, 17 green. Others may go with a documented, owned mitigation.
Signed: Fraud ops lead · Platform lead · Security · Implementation lead (date).
