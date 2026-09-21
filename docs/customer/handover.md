# Handover Document — Template

> Transition from the implementation team to support and the customer's own teams (fictional engagement).
> Completed at the end of hypercare; signed by both sides.

## 1. Scope delivered
| Component | Version | Environment(s) | Notes |
|---|---|---|---|
| decision-service | release 2.0 | prod, staging | channel migration completed (V6 + backfill); V7 scheduled |
| strategies | aldermoor-bank 1.1.0 (prod) | | quillon-pay not in scope for this customer |
| models | aldermoor-bank-lgbm-1.0.0 champion, 1.1.0 shadow | | model refresh cadence agreed: quarterly |
| file-adapter | 1.0 | prod | 4 file types |
| dashboards / alerts | 17 rules | prod | routed to <on-call> |

## 2. Support model
| Level | Who | Scope | Hours | Contact |
|---|---|---|---|---|
| L1 | Customer operations | Monitoring, known procedures (playbook), first triage | 24×7 | |
| L2 | Vendor support | Diagnosis, workarounds, configuration issues | per contract | |
| L3 | Vendor engineering | Defects, product changes | per contract | |

Severity definitions and response times: see CUSTOMER_COMMUNICATION.md §3.

## 3. Operational documentation handed over
- [ ] Troubleshooting playbook (18 incident procedures) — adapted to the customer's environment
- [ ] Go-live and rollback runbooks; migration runbook
- [ ] Architecture, integration contracts, design decision records
- [ ] Dashboards and alert catalogue with runbook links
- [ ] Access list (who has which role: admin, approver, analyst), credential rotation procedure

## 4. Routine tasks and owners
| Task | Frequency | Owner | Procedure |
|---|---|---|---|
| Review KPIs (detection, false declines, review volume) | Weekly | Fraud ops lead | dashboard |
| Strategy changes | On demand | Risk manager + approver | strategy lifecycle |
| Check rejected files / reconciliation | Daily | L1 | FILE_INTEGRATIONS.md |
| DLT check and redrive | Daily | L1 | playbook TS-07 |
| Model monitoring (score drift, PSI — to be implemented) | Monthly | Data scientist | MODEL_STRATEGY.md |
| Credential rotation + rolling restart | Per policy | Platform | TS-16 A |

## 5. Known issues and open items
| # | Item | Impact | Owner | Due |
|---|---|---|---|---|
| 1 | No platform-side monitor-only mode | Parallel runs depend on the gateway | Vendor product | backlog |
| 2 | Adaptive backfill throttle | Manual throttle tuning for data migrations | Vendor engineering | backlog |
| 3 | PSI feature monitoring | Silent feature drift detected only via decision mix | Vendor engineering | backlog |

## 6. Acceptance
Customer confirms: training completed (tracks 1–2), two routine strategy changes performed by customer staff, L1
executed the rollback and the feature-store rebuild procedures in staging, no open SEV1/SEV2.

Signed: <customer service owner> · <vendor implementation lead> · date
