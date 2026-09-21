# Integration Readiness Checklist

> One checklist per integration, completed before end-to-end testing and again before go-live (fictional
> engagement). The first table is filled for the case-management integration as an example; the evidence
> column points to what this repository contains.

## Checklist (per integration)
| # | Item | I-04 Case management (example) | Evidence |
|---|---|---|---|
| 1 | Owner on both sides named; support contacts and hours known | Fraud ops platform team / implementation engineer | – |
| 2 | Contract versioned and agreed (OpenAPI / event schema / file spec) | `POST /cases/v1/cases`, `Idempotency-Key` = decision ID | simulator contract, API_INTEGRATIONS.md |
| 3 | Authentication and network path tested (mTLS/OAuth2, firewall, DNS) | test env only | – |
| 4 | Timeouts: connect, attempt, overall deadline agreed | 500 ms / 1.5 s / 5 s | application.yml |
| 5 | Retry policy safe for the operation (idempotent?) | 4 attempts, back-off 200 ms, idempotent on the key | IntegrationClient tests |
| 6 | Circuit breaker thresholds and behaviour when open | opens on failures; records go to DLT; **pause-on-open proposed** (J-31) | TS-07b |
| 7 | Failure behaviour agreed with the business (what happens to the payment / case) | payment unaffected; case `PENDING_EXTERNAL` → reconciliation job | TS-07b |
| 8 | Capacity: expected rate and peak, tested against the real test system | ~90 cases/s at 150 TPS with 60% REVIEW in the lab | J-27, J-33 |
| 9 | Duplicate handling end to end | internal dedup + unique case per decision + idempotent API: 0 duplicates for 2,278 replays | TS-08 |
| 10 | Error mapping: which errors are retryable, which go to DLT immediately | 4xx permanent → DLT; 5xx/timeout → retry | EventConsumers |
| 11 | Monitoring: success/failure/latency metrics, alert, dashboard panel | `risk_cases_dispatch_failures_total`, `CaseCreationStalled` | alerts.yml |
| 12 | Runbook for the failure modes | DLT redrive, reconciliation | playbook TS-07 |
| 13 | Test data and test cases agreed; negative tests executed (timeouts, 5xx, malformed responses) | fault injection in simulator | downstream-simulators |
| 14 | Data protection: fields sent are the minimum needed; retention on the receiving side | decision ID, tx ID, customer ID, priority, reason codes | – |
| 15 | Sign-off by both owners | ☐ | – |

## Status across integrations
| Integration | Contract | Auth/network | Failure tests | Capacity | Monitoring | Ready |
|---|---|---|---|---|---|---|
| I-01 Gateway → scoring API | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| I-02 Customer profile | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
| I-03 Device risk vendor | ☐ | ☐ | ☐ (TS-06 in lab) | ☐ | ☐ | ☐ |
| I-04 Case management | ☐ | ☐ | ☐ (TS-07b in lab) | ☐ | ☐ | ☐ |
| I-05 Kafka | ☐ | ☐ | ☐ (TS-07a/08 in lab) | ☐ | ☐ | ☐ |
| I-06..I-09 Files | ☐ | ☐ | ☐ (TS-09/10 in lab) | ☐ | ☐ | ☐ |
| I-10 Reporting extract | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
