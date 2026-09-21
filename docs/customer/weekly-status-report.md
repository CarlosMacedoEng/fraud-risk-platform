# Weekly Status Report — Template and Example

> Fictional example for week 10 of the Aldermoor Bank implementation. The technical facts quoted (tests,
> measurements) are real results from this repository; the project context (dates, people, RAG status) is invented.

**Project:** Fraud risk platform — Aldermoor Bank · **Week:** 10 · **Overall status:** 🟡 Amber
**Author:** Implementation lead · **Distribution:** Sponsor, fraud ops lead, platform lead, PMO

## 1. Summary (3 lines)
Integrations are complete and tested. Performance meets the target on a warm service, but a freshly deployed
service fails it for several minutes; the fix (gradual traffic for new instances) needs the platform team's
ingress change, which puts UAT start (week 11) at risk. Decision needed on the review-capacity plan by Friday.

## 2. Progress this week
| Workstream | Done | Status |
|---|---|---|
| Integrations | Case management, device vendor, profile service; files for history and chargebacks | 🟢 |
| Performance | 150 TPS: p95 15 ms / p99 40 ms warm; cold start p95 327 ms (TS-15); fixed a connection-churn defect that capped throughput at ~157 rps (J-26) | 🟡 |
| Strategy | 1.1.0 validated; simulation on 5,000 recent transactions shared with fraud ops | 🟢 |
| Operations | 17 alert rules; 18 incident procedures rehearsed | 🟢 |

## 3. Next week
* Platform team: ingress slow-start for new pods (owner: platform lead, due Tue).
* Repeat performance test after the change (owner: implementation engineer, due Wed).
* UAT preparation with fraud ops: test cases, analyst accounts (owner: fraud ops lead).

## 4. Decisions needed
| # | Decision | Options | Recommendation | Needed by | Owner |
|---|---|---|---|---|---|
| D-7 | Review capacity during parallel run | Keep 600/day; temporary +2 analysts | +2 analysts for 3 weeks (simulation shows 610–680 reviews/day at the proposed threshold) | Fri | Head of Fraud Ops |

## 5. Top risks and issues (from the RAID log)
| ID | Item | Trend | Mitigation / next step |
|---|---|---|---|
| R-09 | Cold-start latency at every deployment | ↑ | Slow start (in progress); fallback: deploy only in low-traffic windows |
| I-04 | Test case-management system slows to 3 s under load (TS-07b pattern) | → | Joint session with their team; backlog drains after recovery, no data lost |

## 6. KPIs (project)
| Measure | Plan | Actual |
|---|---|---|
| Integration tests passing | 100% | 94/94 (Java suite) |
| Open SEV2+ defects | 0 | 1 |
| Milestone: UAT start | W11 | at risk (W12) |
