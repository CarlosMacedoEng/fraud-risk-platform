# RAID Log (Risks, Assumptions, Issues, Dependencies)

> Fictional engagement. Items marked *(lab)* are grounded in something that actually happened in this
> repository's lab; the rest is illustrative.

## Risks
| ID | Risk | Prob. | Impact | Score | Mitigation | Owner | Status |
|---|---|---|---|---|---|---|---|
| R-01 | Device vendor latency spikes degrade ATO detection | M | H | 12 | 60 ms budget + circuit breaker; degraded share on dashboard; vendor SLO review *(lab: TS-06)* | Impl. engineer | Open |
| R-04 | Rule/threshold change floods the review queue | M | H | 12 | Four-eyes, simulation, canary %, `ReviewShareDrift` alert *(lab: TS-14)* | Fraud ops lead | Open |
| R-06 | Review capacity exceeded after go-live | M | H | 12 | Capacity plan from parallel run; temporary staff | Fraud ops lead | Open |
| R-09 | Latency SLO breached after every deployment (cold JVM) | H | M | 12 | Slow start / warm-up; deploy in low-traffic windows *(lab: TS-15)* | Platform lead | Open |
| R-11 | Online data migration slows scoring | M | H | 12 | Throttled backfill at the measured safe rate; stop criterion *(lab: migration §6)* | Impl. engineer | Open |
| R-12 | Messaging consumers stop silently | L | H | 8 | Outcome alerts (`CaseCreationStalled`) *(lab: TS-07a)*; managed Kafka | Platform lead | Mitigated |
| R-07 | Customer history differs from synthetic assumptions | C | H | 15 | Retrain/validate on customer data before go-live | Data scientist | Open |

## Assumptions
| ID | Assumption | Validated? |
|---|---|---|
| A-01 | Gateway can run in parallel mode (score but ignore) during S0 | To confirm with channels team |
| A-02 | PAN tokenised upstream | Yes |
| A-03 | 6–12 months of labelled history available | Partially (8 months) |

## Issues
| ID | Issue | Impact | Action | Owner | Due |
|---|---|---|---|---|---|
| I-04 | Test case-management system slow under load | UAT data delayed | Joint capacity session | Customer CM team | W11 |

## Dependencies
| ID | Dependency | Needed by | Status |
|---|---|---|---|
| D-01 | Staging environment sized like production for performance tests | W10 | Late (W11) |
| D-02 | Ingress slow-start capability | W11 | In progress |
| D-03 | Named approvers for strategy changes (four-eyes) | W12 | Done |

Scoring: probability × impact (L=1…C=5). Reviewed weekly; top items appear in the status report.
