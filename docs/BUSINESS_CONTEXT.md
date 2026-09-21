# Business Context

> Fictional customers, synthetic data. Market statements are general and qualitative; no external statistics
> are quoted. Numbers in §4 come from this project's own evaluation on synthetic data
> ([MODEL_STRATEGY.md](MODEL_STRATEGY.md)) and are illustrations of the method, not predictions for a real bank.

## 1. The problem a fraud platform solves

A payment institution has to make one decision for every transaction, usually in well under a second: let it
through, stop it, or hold it for review. Each outcome has a cost:

| Outcome | Cost to the business |
|---|---|
| Fraud approved | Direct loss (or chargeback), reimbursement obligations, regulatory attention |
| Genuine payment declined ("false decline") | Lost revenue and interchange, customer frustration, churn; for a PSP, merchant churn |
| Payment sent to review | Analyst time, customer friction (step-up, call-back), delayed payments |
| Slow or unavailable decision | Timeouts at the gateway → fallback policy (blind approve or blind decline) |

The job is to minimise the **total** cost, not only fraud losses, within operational limits: review capacity,
latency budget, regulatory duties (explainability, audit) and the customer's risk appetite.

## 2. Why this is hard (and why customers need implementation help)

* **Fraud adapts.** Patterns change in weeks (account takeover, authorised push-payment scams, mule networks);
  models trained on last quarter miss the new pattern. Rules and graph signals cover what the model hasn't learned.
* **Labels are late and partial.** Chargebacks arrive weeks later; many fraud cases are never confirmed.
  Evaluation must respect that (this project found only 11.5% of fraud labelled at the training cut-off, J-01).
* **Every customer is different.** A bank with 14 analysts and a PSP that wants fully automated decisions need
  different thresholds, policies and fallbacks from the same product.
* **The estate is mixed.** Modern REST/event systems next to nightly files from a core banking system.
* **Operations matter as much as models.** A great model behind a slow, opaque or fragile integration still
  fails: timeouts become blind decisions, and incidents erode trust.

## 3. Customer situations in this project

| | Aldermoor Bank (primary) | Quillon Pay (second configuration) |
|---|---|---|
| Business | Retail bank, cards + instant transfers | PSP / e-wallet for online merchants |
| Main risks | Card fraud, account takeover, mule accounts, scams on transfers | Card-not-present fraud, transaction laundering, merchant fraud |
| Constraint | 14 analysts → review budget | Tiny team → wants automated decisions, low review rate |
| Legacy | Core banking files, chargeback files | Merchant API modern; batch labels |
| What "good" means | Fewer losses without flooding analysts or declining loyal customers | Few false declines (merchant churn) at acceptable loss |

## 4. How value is measured (method, shown on synthetic data)

| KPI | Definition | Why it matters |
|---|---|---|
| Fraud detection (value) | Fraud € caught ÷ fraud € total, at the operating threshold | Loss reduction |
| False-decline count / rate | Genuine payments declined | Revenue, customer experience |
| Review rate | Share of transactions sent to review vs capacity | Operational feasibility |
| Incident recall | Share of attacks where ≥ 1 transaction was flagged | Early detection of campaigns |
| Estimated total cost | missed fraud € + false declines × cost + reviews × cost | One number for trade-off discussions |
| Latency p99 / availability | Decision service SLOs | Avoids blind fallback decisions |

Examples from the project's evaluation (synthetic data, assumptions stated in MODEL_STRATEGY.md):
* On known patterns, ML alone had the best PR-AUC (0.748); the **hybrid** strategy earned its place on incident
  recall (0.84 → 0.99) and on an emerging account-takeover variant (≈ 18% → ≈ 39% caught).
* For Quillon, strategy 1.0.0 produced **183 false declines in 18 days**; 1.1.0 removed them at the price of more
  missed fraud. Which one is better depends on the value of a false decline: break-even at about **€39** each.
  That is a business decision; the platform's job is to make the trade-off visible.

## 5. What the platform provides against these problems

| Business need | Capability in this project | Evidence |
|---|---|---|
| Real-time decisions with reasons | REST scoring API, rules + ONNX models + graph features, reason codes | API tests, [API_INTEGRATIONS.md](API_INTEGRATIONS.md) |
| Change risk policy without releases | Versioned strategies, validation, four-eyes approval, canary %, rollback | TS-13/TS-14, governance tests |
| Stay within review capacity | Thresholds set from a review budget; review-share drift alert | MODEL_STRATEGY.md, alerts |
| Work with legacy systems | File ingestion with validation, quarantine, reconciliation | TS-09/TS-10, FILE_INTEGRATIONS.md |
| Keep deciding when dependencies fail | Latency budgets, circuit breakers, bounded fallback, admission control | TS-06/TS-11/TS-12, perf tests |
| Know what happened | Metrics, alerts, audit trail, stored explanations | OBSERVABILITY_AND_OPERATIONS.md |
| Change safely over time | Expand/contract migrations, online backfill, rollback rehearsed | MIGRATION_AND_UPGRADE_RUNBOOK.md |

## 6. What a customer success engineer adds

The product is necessary but not sufficient. The implementation engineer turns it into an outcome by:
translating the customer's risk appetite into measurable configuration; designing integrations that fail
safely; proving performance in the customer's environment; building trust through clear, honest communication
during incidents; and leaving the customer's team able to run and change the system themselves (training,
runbooks, handover). The documents in [customer/](customer/) are templates for those conversations.
