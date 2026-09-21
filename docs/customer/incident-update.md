# Incident Update — Template and Example

> Template plus an example written from lab evidence (TS-07a/TS-07b). The incident happened in a local lab, not
> at a customer; times are shown as they would be written for a customer.

## Template
**Subject:** [SEV<n>][<Investigating | Identified | Monitoring | Resolved>] <business impact in one line>

| | |
|---|---|
| Status | Investigating / Identified / Monitoring / Resolved |
| Started / detected | <time UTC> / <time UTC> (how detected: alert / customer report) |
| Impact | Who and what is affected, in business terms; numbers with scope |
| Not affected | What still works (reassures and prevents duplicate reports) |
| What we are doing | Actions in progress |
| Workaround | If any |
| Next update | <time UTC> |

After resolution: root cause (plain language), timeline, what we changed, what we will change (owner, date).

## Example — update 1 (Identified)
**Subject:** [SEV2][Identified] New review cases are not being created in the case-management system

| | |
|---|---|
| Status | Identified |
| Started / detected | about 17:32 UTC / 17:47 UTC (noticed during a test run; no alert covered this situation) |
| Impact | REVIEW decisions made since ~17:32 have **not** produced cases in your case-management system. Payments are not affected: approve/decline decisions are working normally |
| Not affected | Real-time scoring, latency, declines, file ingestion |
| What we are doing | The message broker stopped assigning work to the case-creation component. All decisions are safely stored and queued; we are restarting the broker component to restore processing |
| Workaround | None needed for payments; analysts may see a gap in new cases |
| Next update | 18:20 UTC |

## Example — resolution note
**Subject:** [SEV2][Resolved] Review cases delayed between 17:32 and ~18:36 UTC

Case creation was restored at 17:50 UTC and the backlog was fully processed by about 18:36 UTC. **No case was lost:**
every REVIEW decision in the window now has a case. Cause: a fault in the message broker's internal
coordination (the broker kept restarting the work assignment instead of completing it). The trigger is still under
investigation.

What we changed: new alerts that detect "review decisions without new cases", "consumer without assigned
work" and repeated failed rebalances (the rebalance alert fired when the fault recurred two hours later, before
anyone noticed a symptom), and case creation now processes a backlog
about 2.7 times faster (88 → 241 per second). What we will do next: move to a managed, multi-broker Kafka setup for
production (owner: platform lead, due <date>). A written review follows within 10 business days.
