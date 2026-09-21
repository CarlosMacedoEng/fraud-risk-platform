# Evaluation — aldermoor-bank — model aldermoor-bank-lgbm-1.1.0 — strategy 1.0.0

> Synthetic data. Test window = most recent 20% of the period, never used for training or tuning.
> Test: 154,056 transactions, 319 fraud, 2026-04-07 → 2026-04-30.
> Review budget 0.3% of daily volume; decline precision target 80%; costs: false decline €10, review €3, missed fraud = transaction amount.

| Variant | Precision | Recall | F1 | ROC-AUC | PR-AUC | FPR | FNR | P@capacity | Reviews/day | Declines/day | Legit flagged ‰ | Fraud € missed | Est. cost € | Incident recall |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| rules_only | 0.184 | 0.4169 | 0.2553 | 0.8112 | 0.3396 | 0.00384 | 0.5831 | 0.242 | 30.1 | 0.0 | 3.838 | 53841.74 | 56010.74 | 0.371 |
| lr_only | 0.3448 | 0.5643 | 0.4281 | 0.9887 | 0.6045 | 0.00222 | 0.4357 | 0.3822 | 21.8 | 0.0 | 2.225 | 24128.21 | 25694.21 | 0.686 |
| ml_only | 0.4165 | 0.6959 | 0.5211 | 0.9897 | 0.6983 | 0.00202 | 0.3041 | 0.4352 | 22.2 | 0.0 | 2.023 | 13136.15 | 14735.15 | 0.986 |
| hybrid_rules_ml | 0.4096 | 0.6959 | 0.5157 | 0.9897 | 0.6983 | 0.00208 | 0.3041 | 0.4352 | 22.6 | 0.0 | 2.081 | 13136.15 | 14762.15 | 0.986 |
| hybrid_full | 0.3696 | 0.6395 | 0.4684 | 0.9859 | 0.6408 | 0.00226 | 0.3605 | 0.4076 | 23.0 | 0.0 | 2.264 | 17875.53 | 19531.53 | 0.9 |
| strategy_as_configured | 0.2338 | 0.6458 | 0.3433 | 0.9893 | 0.6476 | 0.00439 | 0.3542 | 0.3779 | 30.7 | 6.0 | 4.391 | 16796.4 | 19024.4 | 0.843 |

## Recall by fraud type (transaction level)

| Variant | account_takeover | account_takeover (emerging variant) | card_testing | fraud_ring | geo_counterfeit | stolen_card | transaction_laundering |
|---|---|---|---|---|---|---|---|
| rules_only | 0.163 | 0.163 | 0.615 | 0.074 | 0.048 | 0.857 | 0.0 |
| lr_only | 0.102 | 0.102 | 0.904 | 0.963 | 0.0 | 0.933 | 0.062 |
| ml_only | 0.408 | 0.408 | 1.0 | 1.0 | 1.0 | 0.962 | 0.015 |
| hybrid_rules_ml | 0.408 | 0.408 | 1.0 | 1.0 | 1.0 | 0.962 | 0.015 |
| hybrid_full | 0.286 | 0.286 | 0.885 | 1.0 | 0.81 | 0.933 | 0.031 |
| strategy_as_configured | 0.327 | 0.327 | 0.885 | 1.0 | 0.81 | 0.914 | 0.062 |

## Thresholds and weights (chosen on validation window)

| Variant | Weights | Review | Decline |
|---|---|---|---|
| rules_only | None | 0.5 | 1.01 |
| lr_only | None | 0.998074 | 1.01 |
| ml_only | None | 0.009058 | 1.01 |
| hybrid_rules_ml | {'model': 1.0} | 0.009058 | 1.01 |
| hybrid_full | {'model': 1.0, 'rules': 0.7, 'graph': 0.4, 'anomaly': 0.4} | 0.400688 | 1.01 |
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
| supervised | 0.015 | 0.028 | 0.048 |
| anomaly | 0.824 | 1.041 | 1.282 |
