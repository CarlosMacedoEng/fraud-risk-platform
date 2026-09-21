# Training Agenda — Enablement for the Customer's Teams

> Fictional engagement. Three tracks, hands-on on the staging environment. Materials: the documents and lab
> scripts in this repository (adapted to the customer's environment).

## Track 1 — Fraud analysts and risk managers (½ day)
| Module | Content | Hands-on |
|---|---|---|
| 1. Reading a decision | Decision, risk score, reason codes, degraded modes, explanation (SHAP when available) | Open 5 REVIEW decisions and explain each |
| 2. The review queue | Priorities, outcomes, how outcomes become labels | Resolve cases; see the label |
| 3. Strategies | Thresholds, rules, lists, channel policies; version history and audit | Read the diff between 1.0.0 and 1.1.0 |
| 4. Safe changes | Draft → simulate → approve (four-eyes) → canary → rollback | Create a draft, simulate it, request approval |
| 5. Emergency rules | Block a device or BIN in minutes | Emergency derive, then rollback |
| 6. What to watch | Review-share drift, decline spikes, complaints | Dashboard tour |

## Track 2 — Platform operators / L1 support (1 day)
| Module | Content | Hands-on |
|---|---|---|
| 1. Architecture and dependencies | Services, data stores, integrations, what fails how | Draw the flow from memory |
| 2. Health and metrics | Readiness vs liveness, golden signals, degraded modes, alerts | Trigger an alert in staging |
| 3. First 15 minutes | Playbook triage method | Tabletop: TS-06 (slow vendor) |
| 4. Common procedures | Strategy rollback, feature-store rebuild, DLT redrive, file re-delivery | Run each in staging |
| 5. Diagnostics bundle | Thread dump (virtual threads!), JFR, heap info, logs with correlation ID | Collect a bundle |
| 6. Escalation | Severity, what to include in a ticket, when to call L2 | Write an incident update |

## Track 3 — Customer engineers (½ day, optional)
Integration contracts (API, events, files), idempotency, error model, adding a new file type, running the
performance harness, reading the migration runbook.

## Assessment
Each participant completes one hands-on task per module; the trainer signs off competence for L1 procedures.
Feedback form after each track; the materials are updated from the feedback.
