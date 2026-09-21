# Evaluation — quillon-pay — model quillon-pay-lgbm-1.0.0 — strategy 1.1.0

> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.
> Test: 93,482 transactions, 540 fraud, 2026-04-14 → 2026-05-01.
> Review budget 0.2% of daily volume; decline precision target 80%; costs: false decline €10, review €3, missed fraud = transaction amount.

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | FNR | P@capacity | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rules_only | 0.3943 | 0.2556 | 0.3101 | 0.6328 | 0.2113 | 0.00228 | 0.7444 | 0.544 | 17.1 | 2.3 | 2.281 | 46494.35 | 47428.35 | 0.902 |
| lr_only | 0.8969 | 0.3704 | 0.5242 | 0.972 | 0.744 | 0.00025 | 0.6296 | 0.8756 | 12.4 | 0.0 | 0.247 | 30672.15 | 31341.15 | 0.706 |
| ml_only | 0.9959 | 0.4519 | 0.6217 | 0.9935 | 0.8859 | 1e-05 | 0.5481 | 0.9741 | 13.6 | 0.0 | 0.011 | 52173.09 | 52908.09 | 0.471 |
| hybrid_rules_ml | 0.806 | 0.4463 | 0.5745 | 0.9912 | 0.851 | 0.00062 | 0.5537 | 0.9741 | 14.9 | 1.7 | 0.624 | 45897.99 | 46701.99 | 0.549 |
| hybrid_full | 0.786 | 0.3944 | 0.5253 | 0.9961 | 0.8996 | 0.00062 | 0.6056 | 0.9741 | 13.4 | 1.7 | 0.624 | 38110.44 | 38833.44 | 0.706 |
| strategy_as_configured | 0.8726 | 0.7481 | 0.8056 | 0.9961 | 0.8996 | 0.00063 | 0.2519 | 0.9741 | 22.6 | 3.1 | 0.635 | 17657.91 | 18878.91 | 0.725 |

## Recall by fraud type (transaction level)

| Variant | account_takeover | account_takeover (emerging variant) | card_testing | fraud_ring | geo_counterfeit | stolen_card | transaction_laundering |
|---|---|---|---|---|---|---|---|
| rules_only | 0.357 | 0.357 | 0.87 | 0.773 | 0.25 | 0.849 | 0.022 |
| lr_only | 0.036 | 0.036 | 0.696 | 0.727 | 0.25 | 0.849 | 0.24 |
| ml_only | 0.0 | 0.0 | 0.696 | 0.273 | 0.5 | 0.63 | 0.431 |
| hybrid_rules_ml | 0.0 | 0.0 | 0.826 | 0.409 | 0.5 | 0.753 | 0.373 |
| hybrid_full | 0.0 | 0.0 | 0.783 | 0.773 | 0.5 | 0.74 | 0.283 |
| strategy_as_configured | 0.0 | 0.0 | 0.891 | 0.818 | 1.0 | 0.904 | 0.749 |

## Thresholds and weights (chosen on validation window)

| Variant | Weights | Review | Decline |
|---|---|---|---|
| rules_only | None | 0.2 | 0.7 |
| lr_only | None | 0.999572 | 1.01 |
| ml_only | None | 0.601303 | 1.01 |
| hybrid_rules_ml | {'model': 1.0, 'rules': 0.7, 'graph': 0.0, 'anomaly': 0.0} | 0.621576 | 0.906788 |
| hybrid_full | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.742957 | 0.944625 |
| strategy_as_configured | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.6 | 0.9 |

## Rule performance

Validation precision uses labels known at training time (a lower bound); test uses ground truth.

| Rule | Hits (valid) | Precision (valid, observed) | Hits (test) | Precision (test) |
|---|---|---|---|---|
| EMR-001 | 0 | None | 0 | None |
| VEL-001 | 24 | 0.5833 | 27 | 0.7037 |
| VEL-002 | 18 | 0.6667 | 24 | 0.625 |
| DEV-001 | 17 | 0.3529 | 24 | 0.375 |
| GEO-001 | 64 | 0.4219 | 96 | 0.8854 |
| GEO-003 | 18 | 0.6667 | 20 | 0.9 |
| BEN-001 | 6 | 0.8333 | 12 | 0.8333 |
| BEN-003 | 49 | 0.0204 | 58 | 0.0172 |
| BEH-001 | 178 | 0.0787 | 187 | 0.2299 |
| MCC-001 | 1290 | 0.0116 | 1606 | 0.0336 |

## Single-row ONNX inference latency (Python onnxruntime, 1 thread, workbench container)

| Model | p50 ms | p95 ms | p99 ms |
|---|---|---|---|
| supervised | 0.016 | 0.048 | 0.077 |
| anomaly | 0.864 | 1.542 | 2.135 |
