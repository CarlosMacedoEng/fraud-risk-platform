# Evaluation — quillon-pay — model quillon-pay-lgbm-1.0.0 — strategy 1.0.0

> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.
> Test: 93,482 transactions, 540 fraud, 2026-04-14 → 2026-05-01.
> Review budget 0.2% of daily volume; decline precision target 80%; costs: false decline €10, review €3, missed fraud = transaction amount.

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | FNR | P@capacity | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rules_only | 0.3418 | 0.1741 | 0.2307 | 0.6371 | 0.1555 | 0.00195 | 0.8259 | 0.3782 | 13.2 | 2.1 | 1.947 | 69536.79 | 70250.79 | 0.471 |
| lr_only | 0.8969 | 0.3704 | 0.5242 | 0.972 | 0.744 | 0.00025 | 0.6296 | 0.8756 | 12.4 | 0.0 | 0.247 | 30672.15 | 31341.15 | 0.706 |
| ml_only | 0.9959 | 0.4519 | 0.6217 | 0.9935 | 0.8859 | 1e-05 | 0.5481 | 0.9741 | 13.6 | 0.0 | 0.011 | 52173.09 | 52908.09 | 0.471 |
| hybrid_rules_ml | 0.804 | 0.4481 | 0.5755 | 0.9883 | 0.8197 | 0.00063 | 0.5519 | 0.9741 | 15.4 | 1.3 | 0.635 | 45535.63 | 46366.63 | 0.549 |
| hybrid_full | 0.7844 | 0.3907 | 0.5216 | 0.9947 | 0.8657 | 0.00062 | 0.6093 | 0.9793 | 13.7 | 1.2 | 0.624 | 38012.43 | 38753.43 | 0.706 |
| strategy_as_configured | 0.5226 | 0.8574 | 0.6494 | 0.9946 | 0.7815 | 0.00455 | 0.1426 | 0.886 | 20.4 | 28.8 | 4.551 | 10728.58 | 13659.58 | 0.824 |

## Recall by fraud type (transaction level)

| Variant | account_takeover | account_takeover (emerging variant) | card_testing | fraud_ring | geo_counterfeit | stolen_card | transaction_laundering |
|---|---|---|---|---|---|---|---|
| rules_only | 0.107 | 0.107 | 0.587 | 0.091 | 0.25 | 0.726 | 0.022 |
| lr_only | 0.036 | 0.036 | 0.696 | 0.727 | 0.25 | 0.849 | 0.24 |
| ml_only | 0.0 | 0.0 | 0.696 | 0.273 | 0.5 | 0.63 | 0.431 |
| hybrid_rules_ml | 0.0 | 0.0 | 0.826 | 0.409 | 0.75 | 0.767 | 0.371 |
| hybrid_full | 0.0 | 0.0 | 0.783 | 0.773 | 0.75 | 0.74 | 0.275 |
| strategy_as_configured | 0.036 | 0.036 | 0.913 | 1.0 | 1.0 | 0.89 | 0.896 |

## Thresholds and weights (chosen on validation window)

| Variant | Weights | Review | Decline |
|---|---|---|---|
| rules_only | None | 0.5 | 0.9 |
| lr_only | None | 0.999572 | 1.01 |
| ml_only | None | 0.601303 | 1.01 |
| hybrid_rules_ml | {'model': 1.0, 'rules': 0.7, 'graph': 0.0, 'anomaly': 0.0} | 0.625688 | 0.927393 |
| hybrid_full | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.745695 | 0.956024 |
| strategy_as_configured | {'model': 1.0, 'rules': 0.4, 'graph': 0.8, 'anomaly': 0.2} | 0.5 | 0.75 |

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
| BEN-001 | 847 | 0.0071 | 988 | 0.0061 |
| BEN-003 | 49 | 0.0204 | 58 | 0.0172 |
| BEH-001 | 830 | 0.0217 | 1024 | 0.0732 |
| MCC-001 | 1290 | 0.0116 | 1606 | 0.0336 |

## Single-row ONNX inference latency (Python onnxruntime, 1 thread, workbench container)

| Model | p50 ms | p95 ms | p99 ms |
|---|---|---|---|
| supervised | 0.014 | 0.028 | 0.049 |
| anomaly | 0.821 | 1.071 | 1.44 |
