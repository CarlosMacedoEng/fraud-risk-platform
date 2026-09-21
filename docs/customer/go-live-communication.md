# Go-Live Communication — Templates

> Fictional engagement. Three messages: announcement (T-5 days), start, completion. Same structure is used for
> other planned changes (e.g. the release 2.0 migration).

## 1. Announcement (T-5 business days)
**Subject:** [Planned change] Fraud decisioning for POS card payments — go-live on <date>, <time window>

Dear all,

On **<date> between <start> and <end> (UTC)**, Aldermoor Bank will start using the new fraud decision service for
**POS card payments** (stage S1 of the go-live plan). Other channels are not affected in this step.

* **What changes:** POS card payments will be approved, declined or sent to review by the new service.
  E-commerce and transfers stay on the current rules until the next stages.
* **Expected impact:** no downtime. Fraud operations may see a different mix of review cases (plan: up to
  <n> per day).
* **How we protect customers:** the change can be reversed within minutes; latency, errors and review volume are
  watched live on a bridge call; fallback limits for card payments are unchanged (DDR-003).
* **What we need from you:** fraud operations on the bridge from <time>; channels team available for the
  gateway switch.
* **Contacts:** implementation lead <name, phone>; fraud ops lead <name, phone>.

## 2. Start (T-0)
**Subject:** [Started] Fraud decisioning go-live — POS card payments

The change started at <time>. Pre-checks passed (all service instances ready and warmed, alerts green). Next
update at <time + 1 h> or earlier if anything changes.

## 3. Completion
**Subject:** [Completed] Fraud decisioning go-live — POS card payments

The change was completed at <time>. In the first <n> hours: <n> decisions, p99 latency <x> ms (target 250 ms),
<n> review cases (plan <n>), no incidents. The service stays under hypercare with a daily review at <time>.
Next stage (e-commerce canary at 10%) is planned for <date>, subject to the stage exit criteria.

*(If rolled back:)* The change was reversed at <time> because <trigger, in business terms>. No payments were
lost; <n> transactions were decided by the new service before the rollback. We will share the analysis and a new
date by <date>.
