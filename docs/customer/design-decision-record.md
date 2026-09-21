# Design Decision Record (customer-facing)

> Template plus one filled example (fictional). Internal architecture decisions live in [../adr/](../adr/);
> this format is for decisions that need the **customer's** sign-off because they change risk, cost or
> operations.

## Template
| Field | Content |
|---|---|
| ID / title | DDR-nnn — short title |
| Status | Proposed / Accepted / Superseded |
| Date, decision owner (customer), author | |
| Context | The business or technical situation, with numbers |
| Options | 2–3 options with trade-offs (risk, cost, customer impact, operational effort) |
| Recommendation | What we recommend and why |
| Decision | What the customer decided (may differ from the recommendation) |
| Consequences | What changes, what we will monitor, what would make us revisit |
| Sign-off | Names/roles and date |

## Example — DDR-003: Behaviour when the decision service cannot answer in time (card payments)

| Field | Content |
|---|---|
| Status | Accepted (fictional) |
| Decision owner | Head of Fraud Operations, Aldermoor Bank |
| Context | The card gateway waits at most 300 ms for a decision (NFR-02). If the platform cannot answer (timeout, overload, 503 OVERLOADED), the gateway must still decide. Lab evidence: overload is shed quickly (admission control) rather than hanging; a cold JVM after deployment can breach the latency target (TS-15). |
| Options | **A. Fail open** (approve): best customer experience, fraud exposure during outages. **B. Fail closed** (decline): no fraud exposure, mass false declines during outages. **C. Fail open up to an amount per channel, REVIEW/decline above it** |
| Recommendation | **C**: POS fail-open up to €250, e-commerce fail-open up to €100, instant transfers → REVIEW (cannot be recalled once sent). Configured in the strategy's `channelPolicies`, so it is versioned, audited and changeable without a release |
| Decision | C, with the e-commerce limit lowered to €75 by the customer |
| Consequences | The gateway implements the same limits for the "no answer at all" case. Weekly report includes count and value of fail-open decisions; revisit if fail-open value exceeds €X/month or after the first quarter |
| Sign-off | Head of Fraud Ops; Head of Payments; Implementation lead (fictional) |
