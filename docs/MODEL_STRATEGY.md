# Model Strategy

> **Everything in this document is measured on synthetic data** produced by the project's own
> generator (seed 42). The author also wrote the generator, so rules and model features are informed
> by knowledge of how fraud was simulated. Results are therefore **optimistic and illustrative** —
> they show the method (time-based validation, label delay, operating-point metrics, cost), not the
> performance one would get on a real portfolio.

Reports referenced below live in [`ml-workbench/reports/`](../ml-workbench/reports/).

## 1. Detection layers

| Layer | Implementation | What it is good at | What it misses |
|---|---|---|---|
| Deterministic rules | JSON rule DSL (ADR-004), shared by Python evaluation and Java engine | Known typologies, policy (e.g. "first transfer > 5,000 to a new beneficiary is always reviewed"), emergency blocks, instant explainability | Anything not written down; noisy rules create false positives |
| Supervised model | LightGBM, 23 features (`fs-1.0`), ONNX, in-process in Java | Combinations of weak signals on known patterns | Patterns absent from labelled history; depends on label quality and delay |
| Transparent baseline | Logistic regression (standardised, balanced weights) | A sanity check and a model that is easy to explain to a model-risk committee | Non-linear interactions |
| Anomaly detection | Isolation Forest on the same features, score mapped to a training percentile | "This doesn't look like normal traffic" — no labels needed | Legitimate-but-rare behaviour (travel, large purchases) also scores high |
| Graph risk | Rolling 30-day entity graph (NetworkX); device fan-in, beneficiary fan-in with confirmed fraud, merchant laundering indicators, account components over shared devices/IPs | Rings, mules, shared bot devices, laundering merchants — signals invisible at single-customer level | Staleness between refreshes (daily in the evaluation); brand-new entities |

### Combination

`risk = 1 − (1 − w_model·p)(1 − w_rules·points/100)(1 − w_graph·g)(1 − w_anomaly·a)`

A noisy-OR: each signal can raise risk on its own, agreement compounds, and every term can be shown
to an investigator. `a` is the anomaly tail signal: 0 below the configured percentile
(`tailStartPercentile`, e.g. 0.99), rising linearly to 1 at the 100th percentile.
Rules with action `REVIEW`/`DECLINE` impose a **minimum** decision regardless of score.
Thresholds are per customer, overridable per segment and channel.

## 2. Feature contract (training/serving parity)

Features are computed by a **streaming algorithm** ([`features.py`](../ml-workbench/src/fraudlab/features.py))
that the Java service re-implements. Parity is enforced by golden files:

* `models/<customer>/parity/feature_parity_fs-1.0.jsonl` — a raw event stream + profiles + expected features;
* `models/<customer>/<version>/golden_scores.jsonl` — feature vectors + expected ONNX outputs.

Velocity windows count **previously processed** events with `ts > t − window` (epoch ms). That exact
definition matters: an off-by-one on window boundaries is a classic source of silent training/serving skew.

## 3. Validation design (avoiding leakage)

```
|warm-up|------ train ------|-- valid --|-- label-maturation gap --|------ test ------|
 0%     10%                 52%         67%                        80%               100%
                                                                   ^ model trained here (label cut-off)
```

* **Time-based**, never random: the test window is the most recent 20% and is used only for final reporting.
* **Label delay.** Only labels available at the cut-off are used to fit, early-stop and tune.
  First attempt put validation immediately before the cut-off: only **11.5%** of validation fraud was
  labelled and early stopping ran on 26 positives. Adding a **maturation gap** raised validation label
  coverage to **34%** (Aldermoor). Train-window coverage is 90.5%.
* **Class imbalance** (0.17% / 0.46% fraud). No re-weighting of LightGBM (it distorts probabilities).
  Imbalance is handled where it belongs — in threshold selection against a review budget — and
  evaluated with PR-AUC and operating-point metrics rather than accuracy or ROC-AUC alone.
* **Early stopping** uses log-loss, not average precision: with ~55 labelled validation positives AP is
  noisy (experiment in [`experiments/early_stopping.py`](../ml-workbench/experiments/early_stopping.py)).
* **Test metrics use ground truth** — equivalent to re-evaluating once labels have matured.

**Operating point.** Review threshold = score at which the validation flag rate equals the review
budget (Aldermoor 0.3%, Quillon 0.2% of daily volume). Decline threshold = lowest score with ≥ 80%
precision on validation observed labels; when observed labels are too sparse to support that
estimate the decline threshold becomes a documented business decision.

**Cost model (assumptions):** missed fraud = transaction amount; false decline = €10; review = €3.

## 4. Results — Aldermoor Bank (test window: 24 days, 154,056 transactions, 319 fraud)

Model `aldermoor-bank-lgbm-1.0.0`, strategy `1.1.0`
([full report](../ml-workbench/reports/aldermoor-bank/evaluation-aldermoor-bank-lgbm-1.0.0-strategy-1.1.0.md)).

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Rules only | 0.152 | 0.517 | 0.235 | 0.791 | 0.468 | 0.60% | 44.1 | 1.0 | 5.97 | 50,148 | 53,332 | 0.557 |
| Logistic regression | 0.327 | 0.524 | 0.402 | 0.960 | 0.550 | 0.22% | 21.3 | 0 | 2.24 | 28,152 | 29,685 | 0.557 |
| ML only (LightGBM) | **0.482** | **0.762** | **0.591** | 0.983 | **0.748** | 0.17% | 21.0 | 0 | 1.70 | **13,263** | **14,775** | 0.843 |
| Hybrid: rules + ML | 0.482 | 0.756 | 0.589 | 0.982 | 0.731 | 0.17% | 20.8 | 0 | 1.69 | 16,932 | 18,432 | 0.957 |
| Hybrid: + graph + anomaly | 0.439 | 0.712 | 0.543 | **0.989** | 0.741 | 0.19% | 21.5 | 0 | 1.89 | 16,372 | 17,923 | **0.986** |
| Strategy 1.1.0 as configured | 0.331 | 0.709 | 0.451 | 0.989 | 0.741 | 0.30% | 23.8 | 4.7 | 2.97 | 16,376 | 18,099 | 0.986 |

Recall by typology (transaction level):

| Variant | ATO (all emerging in test) | Card testing | Fraud ring | Geo counterfeit | Stolen card | Tx laundering |
|---|---|---|---|---|---|---|
| Rules only | **0.408** | 0.846 | 0.074 | 0.048 | 0.924 | 0.015 |
| ML only | 0.184 | 0.981 | 1.000 | 1.000 | 0.962 | **0.523** |
| Hybrid: + graph + anomaly | 0.388 | 0.885 | 1.000 | 0.762 | 0.943 | 0.308 |

### What these numbers say (and don't)

1. **On patterns seen in training, the supervised model alone is the strongest single variant**
   (best PR-AUC, F1 and cost). On synthetic data a gradient-boosted model learns the generator's
   signals well; on real data the gap would be smaller and noisier.
2. **Hybrid strategies win on incident recall and on the unseen pattern.** The emerging ATO variant
   (domestic IP, moderate amounts, mule beneficiaries) appears only in the test window. ML alone
   catches 18% of its transactions; strategies that include the new-device + new-beneficiary rule and
   graph signals catch ~39–41%. Incident recall (≥ 1 transaction of an attack flagged) goes from 0.84
   (ML only) to 0.99 (hybrid). **That is the argument for keeping rules and non-supervised signals:
   coverage of what the model hasn't learned, plus policy and explainability — not higher PR-AUC on
   known fraud.**
3. **Hybrid costs more euros here** (€17.9k vs €14.8k) because rules push some genuine traffic above
   the review threshold and consume review capacity that ML would have spent on known fraud. This is
   a real trade-off to discuss with the customer, not something to hide.
4. **Transaction laundering** is weakly detected by every variant (≤ 52%). It is a merchant-level
   problem; an issuing bank sees only its own cardholders' slice of the merchant's traffic.

## 5. Results — Quillon Pay (test window: 18 days, 93,482 transactions)

Strategy comparison ("as configured"), model `quillon-pay-lgbm-1.0.0`:

| Strategy | Precision | Recall | F1 | Declines/day | False declines | Legit flagged ‰ | Fraud € missed | Est. cost € |
|---|---|---|---|---|---|---|---|---|
| 1.0.0 | 0.523 | 0.857 | 0.649 | 28.8 | **183** | 4.55 | 10,729 | **13,660** |
| 1.1.0 | **0.873** | 0.748 | **0.806** | 3.1 | **0** | 0.64 | 17,658 | 18,879 |

Strategy 1.0.0 declined 183 genuine payments in 18 days (decline precision 65%). Strategy 1.1.0 removes
them, at the price of ~€7k more missed fraud under the cost assumptions. **Which one is "better" depends
on the value Quillon places on a false decline** — at €10 it is 1.0.0; above ~€39 per false decline
(break-even: 11,830 + 183·X = 18,879), e.g. once merchant churn is priced in, it is 1.1.0. This is exactly the conversation a solutions engineer should drive with data
rather than decide alone. The 1.0.0 behaviour is reused as troubleshooting incident TS-13
(*incorrect risk configuration*).

## 6. Model versions and promotion decisions

| Version | Features | Valid PR-AUC (ground truth) | Test PR-AUC (ML only) | Decision |
|---|---|---|---|---|
| aldermoor-bank-lgbm-1.0.0 | fs-1.0 base (23) | 0.768 | 0.748 | **Champion** |
| aldermoor-bank-lgbm-1.1.0 | base + 4 graph features | 0.649 | 0.698 | Rejected at validation gate |
| quillon-pay-lgbm-1.0.0 | base | 0.907 | 0.886 | **Champion** |
| quillon-pay-lgbm-1.1.0 | base + graph | 0.954 | 0.879 | Passed validation gate → **shadow mode** (strategy 1.1.0) |

Quillon 1.1.0 is the textbook case for shadow mode: it looked better on validation but, in hindsight,
at the configured operating point it recalls less (0.48 vs 0.75 under strategy 1.1.0). Promotion would
have been a regression. Shadow scoring in production would have surfaced that before any customer impact.

Promotion gate (documented policy, enforced manually in this project):
1. Validation PR-AUC ≥ champion − 0.01 and operating-point recall at the review budget ≥ champion.
2. ONNX export parity < 1e-4 and Java golden-score test green.
3. ≥ 7 days in shadow mode with decision-change rate reviewed by fraud operations.
4. Four-eyes approval; `ModelVersionPromoted` event and audit record.

## 7. Explainability

* **Hot path (Java):** reason codes from rules that fired, graph entity that contributed, anomaly
  tail, and a mapped `HIGH_MODEL_SCORE` when the model dominates the score.
* **Investigator view (model-service):** exact TreeSHAP contributions for the stored feature vector
  (`POST /v1/explanations`), top positive contributions mapped to reason codes; channel/type flags
  excluded because they are not actionable reasons.
* **Global view:** [`shap_importance.png`](../ml-workbench/reports/aldermoor-bank/shap_importance.png).
* **Contrasting examples:** [`explanations.md`](../ml-workbench/reports/aldermoor-bank/explanations.md).
  E.g. two e-commerce payments of ~€420–440 for the same segment: one reviewed (amount 33× the
  customer's baseline, low-tenure account, graph risk 1.0 from a shared device, 04:00) and one approved
  (19× baseline but on a known device, no graph links, normal hour).

### False positives and false negatives
* **Typical false positives:** genuine customers who travel (foreign merchant + foreign IP), buy a new
  phone and immediately make a larger purchase, or pay a new beneficiary a large amount. Mitigations:
  context-aware rules (strategy 1.1.0 requires a *combination* of signals), trusted-device lists,
  customer travel notices, segment-specific thresholds, and step-up authentication instead of decline.
* **Typical false negatives:** low-and-slow ATO from a domestic IP with moderate amounts; laundering
  merchants seen through a thin slice of traffic. Mitigations: graph refresh frequency, mule-account
  intelligence shared across customers, beneficiary-level controls, and faster label feedback.

### Known artefacts of the synthetic data
`hour_of_day` ranks high in global importance because the generator schedules fraud uniformly over
24 h while genuine activity follows daytime habits. On real data this signal would be much weaker; it is
a reminder that a model learns whatever differences exist in its training data, including artefacts.

## 8. Inference latency (Python onnxruntime, 1 thread, workbench container)

| Model | p50 | p95 | p99 |
|---|---|---|---|
| LightGBM (ONNX) | 0.015 ms | 0.028 ms | 0.044 ms |
| Isolation Forest (ONNX, 150 trees) + percentile mapping | 0.83 ms | 1.07 ms | 1.48 ms |

Measured on a developer laptop. Java in-process figures are reported in the performance document.

## 9. Limitations

* Synthetic, author-generated data; optimistic results; no adversarial adaptation.
* Label noise only as delay and non-reporting — no mislabelled chargebacks (e.g. friendly fraud).
* Graph refreshed daily in evaluation; the design target is minutes.
* No probability calibration step (isotonic/Platt) — scores are used as ranks + thresholds.
* Thresholds tuned on a single validation window; no confidence intervals (the test window contains
  only ~50 ATO transactions — per-typology recall has wide uncertainty).

## 10. Interview talking points
* "ML alone had the best PR-AUC on known patterns; the hybrid earned its place on incident recall and on
  an emerging pattern — and I can show the euro cost of that trade-off."
* "Label delay nearly broke my validation: 11% of validation fraud was labelled at training time. A
  maturation gap fixed it — and it's exactly the kind of thing that silently inflates offline metrics."
* "A candidate model that looked better on validation would have regressed in production at the
  operating point. That's why promotion goes through shadow mode."
* "The rule-performance table is a customer-success asset: it tells the customer which legacy rules
  generate false positives and should be retired."
