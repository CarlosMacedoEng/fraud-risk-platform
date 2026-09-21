# Evaluation — aldermoor-bank — model aldermoor-bank-lgbm-1.0.0 — strategy 1.1.0

> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.
> Test: 154,056 transactions, 319 fraud, 2026-04-07 → 2026-04-30.
> Review budget 0.3% of daily volume; decline precision target 80%; costs: false decline €10, review €3, missed fraud = transaction amount.

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | FNR | P@capacity | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rules_only | 0.1524 | 0.5172 | 0.2354 | 0.7914 | 0.4679 | 0.00597 | 0.4828 | 0.327 | 44.1 | 1.0 | 5.971 | 50147.53 | 53331.53 | 0.557 |
| lr_only | 0.3268 | 0.5235 | 0.4024 | 0.9604 | 0.55 | 0.00224 | 0.4765 | 0.3376 | 21.3 | 0.0 | 2.238 | 28152.27 | 29685.27 | 0.557 |
| ml_only | 0.4821 | 0.7618 | 0.5905 | 0.9834 | 0.7484 | 0.0017 | 0.2382 | 0.4671 | 21.0 | 0.0 | 1.698 | 13262.53 | 14774.53 | 0.843 |
| hybrid_rules_ml | 0.482 | 0.7555 | 0.5885 | 0.9818 | 0.7308 | 0.00168 | 0.2445 | 0.4671 | 20.8 | 0.0 | 1.685 | 16932.14 | 18432.14 | 0.957 |
| hybrid_full | 0.4391 | 0.7116 | 0.5431 | 0.9891 | 0.7409 | 0.00189 | 0.2884 | 0.4522 | 21.5 | 0.0 | 1.886 | 16371.67 | 17922.67 | 0.986 |
| strategy_as_configured | 0.3309 | 0.7085 | 0.4511 | 0.9891 | 0.7409 | 0.00297 | 0.2915 | 0.4522 | 23.8 | 4.7 | 2.973 | 16376.07 | 18099.07 | 0.986 |

## Recall by fraud type (transaction level)

| Variant | account_takeover | account_takeover (emerging variant) | card_testing | fraud_ring | geo_counterfeit | stolen_card | transaction_laundering |
|---|---|---|---|---|---|---|---|
| rules_only | 0.408 | 0.408 | 0.846 | 0.074 | 0.048 | 0.924 | 0.015 |
| lr_only | 0.0 | 0.0 | 0.846 | 0.852 | 0.0 | 0.943 | 0.015 |
| ml_only | 0.184 | 0.184 | 0.981 | 1.0 | 1.0 | 0.962 | 0.523 |
| hybrid_rules_ml | 0.408 | 0.408 | 0.942 | 0.889 | 0.952 | 0.962 | 0.415 |
| hybrid_full | 0.388 | 0.388 | 0.885 | 1.0 | 0.762 | 0.943 | 0.308 |
| strategy_as_configured | 0.388 | 0.388 | 0.885 | 1.0 | 0.762 | 0.933 | 0.308 |

## Thresholds and weights (chosen on validation window)

| Variant | Weights | Review | Decline |
|---|---|---|---|
| rules_only | None | 0.2 | 0.85 |
| lr_only | None | 0.999075 | 1.01 |
| ml_only | None | 0.021867 | 1.01 |
| hybrid_rules_ml | {'model': 1.0, 'rules': 0.7, 'graph': 0.0, 'anomaly': 0.0} | 0.141955 | 1.01 |
| hybrid_full | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.400116 | 1.01 |
| strategy_as_configured | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.4 | 0.9 |

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
| BEN-001 | 9 | 0.7778 | 21 | 0.9048 |
| BEN-002 | 357 | 0.0196 | 448 | 0.0045 |
| BEN-003 | 3 | 0.0 | 9 | 0.0 |
| BEH-001 | 432 | 0.044 | 521 | 0.0768 |
| MCC-001 | 667 | 0.0165 | 899 | 0.0745 |
| TIM-001 | 10 | 0.6 | 12 | 0.75 |

## Single-row ONNX inference latency (Python onnxruntime, 1 thread, workbench container)

| Model | p50 ms | p95 ms | p99 ms |
|---|---|---|---|
| supervised | 0.015 | 0.027 | 0.045 |
| anomaly | 0.824 | 1.122 | 1.455 |
