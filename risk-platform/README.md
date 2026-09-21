# risk-platform (Java)

Maven multi-module build — Java 21 language level, Spring Boot 4.1.

| Module | Purpose | Status |
|---|---|---|
| `platform-commons` | Correlation conventions, error codes; (Stage 5–6) event envelope, integration-client template | Stage 3 |
| `decision-service` | Real-time scoring API, strategy engine, ONNX inference, feature store, persistence | Stage 3 |
| `file-adapter` | Legacy file ingestion | Stage 7 |
| `downstream-simulators` | Fake customer systems with fault injection | Stage 5 |

```bash
mvn -B verify                      # all tests (needs Docker for Testcontainers)
mvn -B -pl decision-service -am test -Dtest='*Parity*,*Golden*'   # cross-language parity only
```

## Stage 3 — decision service core

### Business purpose
One real-time decision service with a consistent strategy across channels, returning a decision the
caller can act on — with reasons, versions and latency — even when dependencies misbehave.

### What is implemented

| Capability | Where |
|---|---|
| `POST /v1/decisions` with Jakarta validation, card-token-only (raw PAN rejected) | `api/DecisionController`, `api/dto/ScoreRequest` |
| Idempotency (`Idempotency-Key` per client, request hash, replay, reuse → 422, in-flight → 409) | `persistence/IdempotencyRepository`, `application/DecisionService` |
| Natural-key duplicate protection (same transaction, different key → 409 with existing decision ID) | `DecisionRepository.insertTransaction` |
| API-key authentication (hashed), roles SCORING / ANALYST / ADMIN, tenant from credential | `api/security`, `config/SecurityConfig` |
| Correlation ID accepted/generated, in MDC, response header, JSON logs | `api/CorrelationIdFilter`, `logback-spring.xml` |
| Parallel enrichment on virtual threads with per-dependency budgets | `DecisionService.decide` |
| Feature spec fs-1.0 (Java twin of `features.py`) | `features/FeatureCalculator` |
| Feature store: Redis (pipelined), PostgreSQL fallback behind a circuit breaker, in-memory reference | `features/*FeatureStore` |
| Graph risk lookup from Redis (snapshot loaded at startup) | `features/RedisGraphFeatureStore` |
| In-process ONNX inference, SHA-256-verified artifacts, bounded inference pool (bulkhead) + time budget | `inference/*` |
| Declarative rule DSL: whitelist, full validation, compiled predicates | `strategy/StrategyCompiler` |
| Hybrid decision: noisy-OR, segment/channel thresholds, policy minimums, channel fail policy | `strategy/StrategyEngine` |
| Shadow scoring of a challenger model (stored, never used for the decision) | `ModelScorer.challengerProbability` |
| Strategy versions in PostgreSQL, bootstrapped from version-controlled files, cached, auto-refreshed | `application/ActiveStrategyProvider`, `StrategyBootstrap` |
| Flyway migrations V1 (core) and V2 (strategy/model/audit), constraints, partial indexes | `resources/db/migration` |
| RFC 9457 problem responses with stable `code` + correlation ID | `api/ApiExceptionHandler` |
| Health/readiness (DB, Redis, strategies, models), Prometheus metrics | `observability/*`, Actuator |
| Fault injection (lab profile only) for model and feature-store failures | `lab/FaultInjector` |

### Tests (40, all passing)

| Test | What it proves |
|---|---|
| `FeatureParityTest` | Java features == Python features on the exported stream (both tenants) |
| `RedisFeatureStoreParityTest` | Redis implementation == Python |
| `JdbcFallbackParityTest` | PostgreSQL fallback == Python |
| `OnnxGoldenScoresTest` | Java ONNX Runtime reproduces Python probabilities (±1e-5) and anomaly percentiles for 4 model versions; tampered artifact rejected |
| `AnomalyCalibrationTest` | Percentile mapping == `numpy.interp`, including duplicate x-values |
| `StrategyCompilerTest` | All 4 strategy files valid; invalid documents report every error; tenant mismatch; unknown fields |
| `StrategyEngineTest` | Combination math, channel-scoped rules, policy/emergency minimums, threshold overrides, fail policies |
| `DecisionApiIntegrationTest` | Contract, idempotency (replay/reuse/duplicate), validation, authN/Z, tenant isolation, second tenant, unknown customer, keyset pagination, health/metrics — real PostgreSQL + Redis |
| `ResilienceIntegrationTest` | Model failure → channel fail policy; slow model cut off by budget; Redis failure → PostgreSQL fallback |

### Engineering decisions
* **Virtual threads for I/O-bound enrichment, a small platform-thread pool for CPU-bound inference.**
  Mixing them would either waste cores or let slow inference exhaust request threads.
* **Fallbacks are explicit and visible**: each degradation adds a `degradedModes` entry, a reason and a
  metric. Journal entry J-07 shows why this matters.
* **One database transaction per decision** (transaction row, decision row, idempotency completion,
  and — from Stage 6 — outbox events). The feature store is updated only after commit, so a failed or
  replayed request never counts twice in velocity.
* **Tenant comes from the credential**, never from the payload.

### Limitations (Stage 3)
* API keys instead of OAuth2/mTLS; key rotation not implemented.
* Redis read/record is not atomic per card (documented; Lua script is the hardening path).
* No rate limiting / per-client quotas yet.
* `processingTimeMs` excludes the persistence step; `Server-Timing: total` includes it.

### Interview talking points
* "Every dependency except the database degrades the decision instead of failing it — and the
  response says exactly which signal was missing."
* "Parity tests found two real bugs that graceful degradation had hidden. Since then, every fallback
  path has its own test and its own alert."
* "Idempotency is per client and verified with a request hash, so a retry returns the original
  decision, and reusing a key for a different payload is rejected instead of silently returning the wrong answer."
