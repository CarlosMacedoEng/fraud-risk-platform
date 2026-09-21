# Evaluation — aldermoor-bank — model aldermoor-bank-lgbm-1.0.0 — strategy 1.0.0

> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.
> Test: 154,056 transactions, 319 fraud, 2026-04-07 → 2026-04-30.
> Review budget 0.3% of daily volume; decline precision target 80%; costs: false decline €10, review €3, missed fraud = transaction amount.

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | FNR | P@capacity | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rules_only | 0.184 | 0.4169 | 0.2553 | 0.8112 | 0.3396 | 0.00384 | 0.5831 | 0.242 | 30.1 | 0.0 | 3.838 | 53841.74 | 56010.74 | 0.371 |
| lr_only | 0.3268 | 0.5235 | 0.4024 | 0.9604 | 0.55 | 0.00224 | 0.4765 | 0.3376 | 21.3 | 0.0 | 2.238 | 28152.27 | 29685.27 | 0.557 |
| ml_only | 0.4821 | 0.7618 | 0.5905 | 0.9834 | 0.7484 | 0.0017 | 0.2382 | 0.4671 | 21.0 | 0.0 | 1.698 | 13262.53 | 14774.53 | 0.843 |
| hybrid_rules_ml | 0.4515 | 0.6708 | 0.5397 | 0.9674 | 0.666 | 0.00169 | 0.3292 | 0.4225 | 19.8 | 0.0 | 1.691 | 23182.64 | 24604.64 | 0.771 |
| hybrid_full | 0.408 | 0.7022 | 0.5161 | 0.9843 | 0.6817 | 0.00211 | 0.2978 | 0.431 | 21.8 | 1.1 | 2.114 | 16359.72 | 17928.72 | 0.914 |
| strategy_as_configured | 0.2555 | 0.7335 | 0.3789 | 0.9865 | 0.6713 | 0.00444 | 0.2665 | 0.4055 | 32.5 | 5.7 | 4.436 | 14612.77 | 16962.77 | 0.871 |

## Recall by fraud type (transaction level)

| Variant | account_takeover | account_takeover (emerging variant) | card_testing | fraud_ring | geo_counterfeit | stolen_card | transaction_laundering |
|---|---|---|---|---|---|---|---|
| rules_only | 0.163 | 0.163 | 0.615 | 0.074 | 0.048 | 0.857 | 0.0 |
| lr_only | 0.0 | 0.0 | 0.846 | 0.852 | 0.0 | 0.943 | 0.015 |
| ml_only | 0.184 | 0.184 | 0.981 | 1.0 | 1.0 | 0.962 | 0.523 |
| hybrid_rules_ml | 0.163 | 0.163 | 0.904 | 0.852 | 0.857 | 0.943 | 0.292 |
| hybrid_full | 0.286 | 0.286 | 0.923 | 1.0 | 0.762 | 0.952 | 0.292 |
| strategy_as_configured | 0.347 | 0.347 | 0.904 | 1.0 | 0.762 | 0.933 | 0.446 |

## Thresholds and weights (chosen on validation window)

| Variant | Weights | Review | Decline |
|---|---|---|---|
| rules_only | None | 0.5 | 1.01 |
| lr_only | None | 0.999075 | 1.01 |
| ml_only | None | 0.021867 | 1.01 |
| hybrid_rules_ml | {'model': 1.0, 'rules': 0.5, 'graph': 0.0, 'anomaly': 0.0} | 0.251169 | 1.01 |
| hybrid_full | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.401689 | 0.983629 |
| strategy_as_configured | {'model': 1.0, 'rules': 0.5, 'graph': 0.6, 'anomaly': 0.3} | 0.35 | 0.85 |

## Rule performance

Validation precision uses labels known at training time (a lower bound); test uses ground truth.

| Rule | Hits (valid) | Precision (valid, observed) | Hits (test) | Precision (test) |
|---|---|---|---|---|
| EMR-001 | 0 | None | 0 | None |
| EMR-002 | 0 | None | 0 | None |
| VEL-001 | 5 | 0.0 | 32 | 0.8125 |
| VEL-002 | 12 | 0.0 | 5 | 0.0 |
| DEV-001 | 37 | 0.2162 | 62 | 0.3871 |
| DEV-002 | 90 | 0.1222 | 131 | 0.3588 |
| GEO-001 | 42 | 0.5 | 113 | 0.9735 |
| GEO-002 | 97 | 0.0515 | 124 | 0.0565 |
| GEO-003 | 14 | 0.5 | 24 | 0.9167 |
| BEN-001 | 1409 | 0.0071 | 1695 | 0.0059 |
| BEN-002 | 357 | 0.0196 | 448 | 0.0045 |
| BEN-003 | 3 | 0.0 | 9 | 0.0 |
| BEH-001 | 3646 | 0.0074 | 4855 | 0.0165 |
| BEH-002 | 4801 | 0.0027 | 6353 | 0.0124 |
| MCC-001 | 667 | 0.0165 | 899 | 0.0745 |
| TIM-001 | 10 | 0.6 | 12 | 0.75 |

## Single-row ONNX inference latency (Python onnxruntime, 1 thread, workbench container)

| Model | p50 ms | p95 ms | p99 ms |
|---|---|---|---|
| supervised | 0.015 | 0.027 | 0.04 |
| anomaly | 0.821 | 1.017 | 1.232 |
