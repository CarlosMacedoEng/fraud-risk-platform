# Incident Runbook — <incident class>

> Same structure as every entry in TROUBLESHOOTING_PLAYBOOK.md.

| Section | Content |
|---|---|
| **Detection** | Alert name(s), dashboard panel, typical customer report |
| **Symptoms** | What users/operators see; what is *not* affected |
| **Impact / severity** | Business impact; default severity (SUPPORT_MODEL.md §1) |
| **Hypotheses** | Ordered by likelihood × cost to check |
| **Investigation** | Exact commands/queries, in order, with what each result means |
| **Evidence to capture before fixing** | Thread dump (JSON, for virtual threads), JFR, heap info, `pg_stat_activity`, consumer-group state, logs by correlation ID |
| **Mitigation** | Fastest safe action to restore service (rollback, failover, shed, disable) |
| **Root-cause confirmation** | How to prove it, not just "it went away" |
| **Remediation** | Permanent fix |
| **Prevention** | Test, alert, guardrail, process change |
| **Customer communication** | First message for this incident class (CUSTOMER_COMMUNICATION.md §3) |
| **Follow-up** | Actions with owners and dates; knowledge-base update |
| **Last rehearsed** | Date, environment, link to evidence |
