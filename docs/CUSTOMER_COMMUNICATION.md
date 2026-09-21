# Customer Communication

> Principles and templates for a fictional engagement. The templates live in [customer/](customer/); the
> troubleshooting playbook contains an incident-specific message for each lab incident.

## 1. Principles

1. **Facts first, in the customer's language.** Business impact (payments, customers, cases) before technical
   cause. "About 17% of scoring requests timed out between 10:15 and 10:16" beats "Hikari pool exhausted".
2. **Say what you know, what you don't, and when you'll update.** An update without a root cause is still an
   update. Never guess a cause in writing; label hypotheses as hypotheses.
3. **Numbers with their source and scope.** "In our simulation on 5,000 recent transactions" is different from
   "in production". Customers remember numbers; wrong ones cost trust.
4. **No surprises.** Risks go into the weekly report before they become issues. Bad news early, with options.
5. **Recommendations, not only options.** Present the trade-off and say what you would do (e.g. the €39 break-even
   for Quillon's false declines, MODEL_STRATEGY.md).
6. **Close the loop.** Every incident ends with prevention actions, owners and dates, then a follow-up that they
   were done.
7. **Write for forwarding.** A status report or incident update will be forwarded to people who were not in the
   meeting: self-contained, dated, no internal jargon.

## 2. Audiences and channels

| Audience | Cares about | Channel | Cadence |
|---|---|---|---|
| Executive sponsor | Outcome, timeline, risk, cost | Steering committee, monthly summary | Monthly / at gates |
| Fraud operations lead | Detection, false declines, review workload, control of changes | Status report, daily hypercare call | Weekly / daily in hypercare |
| Platform / DBA team | Capacity, changes, incidents, runbooks | Change tickets, technical reviews | Per change |
| Risk & compliance | Explainability, audit, approvals, model governance | Design decision records, audit exports | At design and model changes |
| Analysts | How to use the tool, what changed | Training, release notes | At changes |

## 3. Incident communication

| Severity | First message | Updates | Closing |
|---|---|---|---|
| SEV1 | ≤ 15 min after detection (phone + written) | Every 30 min | Resolution note ≤ 2 h after recovery; written review ≤ 5 business days |
| SEV2 | ≤ 30 min | Every 60 min | Resolution note same day; review ≤ 10 business days |
| SEV3 | Same business day | At changes | In the weekly report |

Template: [customer/incident-update.md](customer/incident-update.md). Structure: status · impact (who, what, since
when) · what we are doing · workaround · next update time. Examples written from lab evidence: every incident in
[TROUBLESHOOTING_PLAYBOOK.md](TROUBLESHOOTING_PLAYBOOK.md) has a "Customer communication" paragraph.

## 4. Difficult conversations (how I would handle them)

| Situation | Approach |
|---|---|
| The customer asks for a guarantee ("zero false declines") | Explain the trade-off with their own numbers; offer a measurable target and a monitoring plan instead of a promise |
| A result is worse than expected (e.g. a cold JVM fails the SLO, TS-15) | Show the measurement, the cause and the mitigation plan; don't hide the failed test |
| The customer's system causes the incident (slow case management, TS-07b) | Describe the impact and the evidence neutrally; propose joint actions (timeouts, capacity); no blame |
| Pressure to skip a gate before go-live | Make the risk explicit in writing, with the specific criterion and what could happen; let the accountable person decide; record the decision |
| A recommendation is rejected | Record the decision and its rationale in a design decision record; agree on the signal that would reopen it |

## 5. Templates

| Document | Use |
|---|---|
| [discovery-questionnaire.md](customer/discovery-questionnaire.md) | Discovery workshops |
| [architecture-review-agenda.md](customer/architecture-review-agenda.md) | Design phase review |
| [design-decision-record.md](customer/design-decision-record.md) | Decisions that need customer sign-off |
| [weekly-status-report.md](customer/weekly-status-report.md) | Weekly status |
| [risk-report.md](customer/risk-report.md) | RAID log |
| [production-readiness-checklist.md](customer/production-readiness-checklist.md) | Go / no-go |
| [go-live-communication.md](customer/go-live-communication.md) | Change announcements |
| [incident-update.md](customer/incident-update.md) | Incident communication |
| [post-go-live-review.md](customer/post-go-live-review.md) | Review after hypercare |
| [training-agenda.md](customer/training-agenda.md) | Enablement |
| [handover.md](customer/handover.md) | Transition to support / customer ownership |
