# Engineering Journal

Real issues found while building this project (not simulated incidents). Each entry records what
happened, how it was found and what changed. This is the most honest source for interview stories:
everything here actually occurred during development, on synthetic data, on a laptop.

| # | Stage | Issue | How it was found | Fix / lesson |
|---|---|---|---|---|
| J-01 | 2 | Only **11.5%** of validation-window fraud was labelled at the training cut-off; early stopping ran on 26 positives | Printed label coverage per split before trusting metrics | Added a label-maturation gap between validation and cut-off (coverage → 34%). Lesson: label delay silently inflates or destabilises offline metrics |
| J-02 | 2 | Early stopping on average precision stopped at 20 trees and was noisy | Experiment comparing stopping criteria using only information available at training time | Switched to log-loss stopping; documented in `experiments/early_stopping.py` |
| J-03 | 2 | Hybrid strategy did **not** beat ML-only on PR-AUC for known patterns | Full evaluation matrix, rule-level precision table | Kept the honest result; tuned noisy rules using *validation* evidence only; hybrid value shown on incident recall and on the emerging pattern |
| J-04 | 2 | Candidate model with graph features was worse on validation (Aldermoor) and, for Quillon, better on validation but worse at the operating point | Promotion comparison across windows | Formal promotion gate + shadow mode before any champion change |
| J-05 | 2 | Quillon strategy 1.0.0 produced 183 false declines in 18 days | Evaluating the strategy *as configured*, not only tuned variants | Strategy 1.1.0; reused as troubleshooting incident TS-13 |
| J-06 | 2 | Stated break-even for false-decline cost was wrong (€50 vs actual ~€39) | Re-derived the arithmetic before publishing | Corrected; lesson: check every number in customer-facing documents |
| J-07 | 3 | **Redis feature reads failed on every request** (`ClassCastException`: pipelined connection is not a `StringRedisConnection`) — hidden because the circuit breaker opened and PostgreSQL fallback took over; API tests still passed | A dedicated Redis-vs-Python parity test | Wrap with `DefaultStringRedisConnection`; happy-path tests now assert `degradedModes` is empty; alert on `risk_featurestore_fallback_reads_total > 0`. **Lesson: graceful degradation hides bugs unless degradation is observable and tested** |
| J-08 | 3 | PostgreSQL fallback "seen device" query was rejected (`? - interval` on an untyped parameter) — again silently degrading | A dedicated PostgreSQL-fallback parity test | `?::timestamptz`; every fallback path now has its own parity test |
| J-09 | 3 | `NullPointerException` from a ternary mixing `long` and a null `Long` (auto-unboxing) | Same parity test on an empty history | Keep both branches boxed; classic Java pitfall worth mentioning in code reviews |
| J-10 | 3 | Validation errors returned in Spring's default format instead of the platform's error contract | Integration test asserting the `code` field | Removed the competing framework handler; mapped `HandlerMethodValidationException` field by field |
| J-11 | 3 | Docker Desktop auto-updated mid-session and the engine was unavailable | Tool error on `docker run` | Restarted the engine; lesson for go-live: pin and freeze tooling during change windows |
