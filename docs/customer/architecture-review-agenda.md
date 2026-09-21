# Architecture Review — Agenda and Checklist

> Template for the design-phase review with the customer's architects, security and operations (fictional
> engagement). 2 × 90 minutes. Pre-read: ARCHITECTURE.md, ADRs, integration contracts, sizing.

## Session 1 — Solution and integrations (90 min)
| Time | Topic | Presenter | Decision needed |
|---|---|---|---|
| 0:00 | Goals, scope, success criteria recap | Implementation lead | – |
| 0:10 | Solution overview: decision service, strategy engine, models, feature store, events, files | Implementation engineer | – |
| 0:30 | Scoring API contract: idempotency, errors, timeouts, versioning | Implementation engineer | API contract sign-off |
| 0:45 | Fail policies per channel on timeout / degradation | Implementation engineer + fraud ops | **Fail-open limits, transfer policy** |
| 1:00 | Events (outbox, at-least-once, idempotent consumers, DLT) and files (validation, quarantine, reconciliation) | Implementation engineer | Topic naming, retention, file SLAs |
| 1:20 | Open questions and actions | Lead | owners/dates |

## Session 2 — Non-functionals, security, operations (90 min)
| Time | Topic | Decision needed |
|---|---|---|
| 0:00 | Sizing from measured CPU cost per request; performance test plan in staging (warm and cold) | Test environment and data |
| 0:20 | Deployment: Kubernetes manifests, probes, rollout, HPA, PDB; managed services (RDS, ElastiCache, MSK) | Hosting responsibilities |
| 0:40 | Security: authentication, secrets, encryption, network, audit, data retention, PCI scope (tokens only) | Security sign-off conditions |
| 0:55 | Observability and alerting; incident process; on-call split | Alert routing, severity model |
| 1:10 | Change management: strategy lifecycle, schema migrations (expand/contract), upgrades | CAB integration |
| 1:25 | Summary of decisions → design decision records | – |

## Review checklist
- [ ] Every integration has an owner, a contract, a timeout, a retry policy and a failure behaviour
- [ ] Every failure mode in the troubleshooting playbook has a detection signal
- [ ] Capacity plan with headroom and the cold-start effect (TS-15) considered
- [ ] Data retention, deletion and audit requirements mapped to tables and logs
- [ ] Secrets never in images or config maps; rotation procedure includes a rolling restart (TS-16 A)
- [ ] Rollback path defined for code, configuration, schema and models
- [ ] Decisions recorded as design decision records with the customer's approver
