# Evaluation — quillon-pay — model quillon-pay-lgbm-1.1.0 — strategy 1.1.0

> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.
> Test: 93,482 transactions, 540 fraud, 2026-04-14 → 2026-05-01.
> Review budget 0.2% of daily volume; decline precision target 80%; costs: false decline €10, review €3, missed fraud = transaction amount.

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | FNR | P@capacity | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rules_only | 0.3943 | 0.2556 | 0.3101 | 0.6328 | 0.2113 | 0.00228 | 0.7444 | 0.544 | 17.1 | 2.3 | 2.281 | 46494.35 | 47428.35 | 0.902 |
| lr_only | 1.0 | 0.213 | 0.3511 | 0.9939 | 0.9379 | 0.0 | 0.787 | 0.9793 | 6.4 | 0.0 | 0.0 | 60705.45 | 61050.45 | 0.49 |
| ml_only | 1.0 | 0.3074 | 0.4703 | 0.9956 | 0.8789 | 0.0 | 0.6926 | 0.9689 | 8.3 | 0.9 | 0.0 | 58204.6 | 58651.6 | 0.549 |
| hybrid_rules_ml | 0.7532 | 0.3278 | 0.4568 | 0.9934 | 0.8364 | 0.00062 | 0.6722 | 0.943 | 11.1 | 2.0 | 0.624 | 50255.22 | 50852.22 | 0.588 |
| hybrid_full | 0.7455 | 0.3093 | 0.4372 | 0.9963 | 0.8684 | 0.00061 | 0.6907 | 0.9482 | 10.6 | 1.8 | 0.613 | 42597.79 | 43170.79 | 0.725 |
| strategy_as_configured | 0.8156 | 0.4833 | 0.607 | 0.9963 | 0.8684 | 0.00063 | 0.5167 | 0.9482 | 15.4 | 2.3 | 0.635 | 28918.32 | 29752.32 | 0.745 |

## Recall by fraud type (transaction level)

| Variant | account_takeover | account_takeover (emerging variant) | card_testing | fraud_ring | geo_counterfeit | stolen_card | transaction_laundering |
|---|---|---|---|---|---|---|---|
| rules_only | 0.357 | 0.357 | 0.87 | 0.773 | 0.25 | 0.849 | 0.022 |
| lr_only | 0.0 | 0.0 | 0.239 | 0.318 | 0.0 | 0.411 | 0.183 |
| ml_only | 0.0 | 0.0 | 0.739 | 0.409 | 0.5 | 0.603 | 0.21 |
| hybrid_rules_ml | 0.0 | 0.0 | 0.804 | 0.5 | 0.75 | 0.795 | 0.185 |
| hybrid_full | 0.0 | 0.0 | 0.783 | 0.818 | 0.75 | 0.712 | 0.158 |
| strategy_as_configured | 0.0 | 0.0 | 0.891 | 0.864 | 1.0 | 0.89 | 0.36 |

## Thresholds and weights (chosen on validation window)

| Variant | Weights | Review | Decline |
|---|---|---|---|
| rules_only | None | 0.2 | 0.7 |
| lr_only | None | 0.999992 | 1.01 |
| ml_only | None | 0.609543 | 0.814178 |
| hybrid_rules_ml | {'model': 1.0, 'rules': 0.7, 'graph': 0.0, 'anomaly': 0.0} | 0.627289 | 0.879815 |
| hybrid_full | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.759075 | 0.925574 |
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
| supervised | 0.014 | 0.029 | 0.046 |
| anomaly | 0.82 | 1.11 | 1.507 |
