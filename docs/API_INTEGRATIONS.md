# API Integrations

> Implemented in Stages 3–5. Contract: [`docs/api/openapi-v1.yaml`](api/openapi-v1.yaml), enforced by
> `OpenApiContractTest`. All external systems are fictional and simulated locally.

## 1. Integration map

```mermaid
flowchart LR
    GW[Gateway / channels] -- "POST /v1/decisions<br/>sync, 300 ms hard deadline" --> DS[decision-service]
    AN[Analysts / admin tools] -- "cases, explanations, admin" --> DS
    DS -- "GET profile · 35 ms · no retry" --> CRM[CRM profile API]
    DS -- "POST assessment · 55 ms · no retry" --> DEV[Device-intel vendor]
    DS -- "POST case · 5 s · 4 attempts · Idempotency-Key" --> CMS[Case management]
    DS -- "POST explanation · 4 s · 2 attempts" --> MS[model-service (Python)]
```

## 2. Inbound: scoring API

`POST /v1/decisions` — headers `X-Api-Key`, `Idempotency-Key` (required), `X-Correlation-Id` (optional).

```bash
curl -s localhost:8080/v1/decisions \
  -H 'X-Api-Key: dev-aldermoor-gateway-key' -H 'Idempotency-Key: ALD-T000900001' -H 'Content-Type: application/json' \
  -d '{"transactionId":"ALD-T000900001","customerId":"ALD-C000123","accountId":"ALD-A000123",
       "eventTime":"2026-04-20T14:03:11.250Z","transactionType":"CARD_PAYMENT","channel":"ECOM",
       "amount":249.90,"currency":"EUR","cardToken":"tok_K000456","merchantId":"M000812","mcc":"5732",
       "merchantCountry":"PT","deviceId":"D000321","ipAddress":"81.20.30.40","ipCountry":"PT"}'
```

Response (shape; values illustrative):

```json
{
  "decisionId": "3f1c…", "transactionId": "ALD-T000900001",
  "decision": "REVIEW", "riskScore": 0.62, "riskLevel": "HIGH", "fraudProbability": 0.41,
  "reasons": [
    {"code": "HIGH_MODEL_SCORE", "source": "MODEL", "description": "Machine-learning model indicates elevated fraud probability", "detail": "model probability 0.410", "contribution": 0.41},
    {"code": "NEW_DEVICE", "source": "RULE", "detail": "New device with high amount", "contribution": 0.175, "ruleId": "DEV-001"}
  ],
  "signals": {"modelProbability": 0.41, "anomalyPercentile": 0.9981, "graphRisk": 0.0, "rulePoints": 35},
  "versions": {"model": "aldermoor-bank-lgbm-1.0.0", "strategy": "1.1.0", "featureSpec": "fs-1.0"},
  "degradedModes": [], "processingTimeMs": 11.4, "idempotentReplay": false,
  "correlationId": "5d0e…", "decidedAt": "2026-04-20T14:03:11.291Z"
}
```

### Semantics
| Concern | Behaviour |
|---|---|
| Authentication | `X-Api-Key` (SHA-256 compared in constant time). Tenant derived from the credential, never from the body. Production assumption: OAuth2 client credentials or mTLS at the gateway |
| Authorisation | Roles: SCORING (score, read), ANALYST (read, explanations, cases), ADMIN (configuration) |
| Idempotency | Keys scoped per client. Same key + same body → original decision (`Idempotent-Replayed: true`). Same key + different body → 422 `IDEMPOTENCY_KEY_REUSED`. Concurrent duplicate → 409 `IDEMPOTENCY_IN_PROGRESS` (retry later). Keys kept 7 days |
| Natural-key duplicates | Same `transactionId` with a new key → 409 `DUPLICATE_TRANSACTION` with `existingDecisionId` |
| Validation | Jakarta Bean Validation; per-field errors; raw PANs rejected; card payments need token + merchant, transfers need beneficiary |
| Degradation | Dependencies other than PostgreSQL never cause a 5xx for valid input; `degradedModes` + reasons say what was missing |
| Latency | `processingTimeMs` = time to decision; `Server-Timing` header includes persistence |
| Correlation | `X-Correlation-Id` accepted if well-formed (≤ 64 chars, `[A-Za-z0-9._-]`), else generated; echoed in header, body, logs, outbound calls, events |

### Error contract (RFC 9457)

```json
{"type": "https://docs.fraud-platform.example/errors/validation-failed", "title": "Request failed validation",
 "status": 400, "detail": "Request failed validation", "code": "VALIDATION_FAILED",
 "correlationId": "it-…", "details": {"errors": [{"field": "currency", "message": "must match \"[A-Z]{3}\""}]}}
```

| HTTP | `code` | Client action |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MISSING_IDEMPOTENCY_KEY` | Fix the request; do not retry unchanged |
| 401 / 403 | `UNAUTHENTICATED` / `FORBIDDEN` | Check credentials/role |
| 404 | `NOT_FOUND` | — |
| 409 | `IDEMPOTENCY_IN_PROGRESS` | Retry the same key after a short delay |
| 409 | `DUPLICATE_TRANSACTION` | Use `existingDecisionId` |
| 409 | `VERSION_CONFLICT` | Re-read, re-apply (optimistic locking) |
| 422 | `IDEMPOTENCY_KEY_REUSED`, `STRATEGY_INVALID`, `INVALID_STATE_TRANSITION` | Fix the request |
| 503 | `DEPENDENCY_UNAVAILABLE` | **Transient only** (DB connection/timeout): retry with the same key and back-off |
| 500 | `INTERNAL_ERROR` | Defect: raise a ticket quoting `correlationId` |

Only transient data-access failures map to 503; everything else is a 500 (journal J-12).

### Versioning and backward compatibility
* URL major version (`/v1`). Within v1: add optional request fields and response fields only; never
  rename, remove, change type or meaning; never make an optional field required.
* Clients must ignore unknown response fields (tolerant reader); the server ignores unknown request fields.
* Enums may gain values (e.g. new reason codes); clients must handle unknown values gracefully.
* A breaking change ships as `/v2` alongside `/v1`, with a deprecation period and a `Deprecation`/`Sunset` header on v1.

## 3. Outbound integrations

All outbound calls use the reusable `IntegrationClient` template (`platform-commons`).

| Integration | Operation | Path in scoring? | Deadline | Attempts | Idempotent | On failure |
|---|---|---|---|---|---|---|
| CRM profile | `GET /crm/v1/tenants/{t}/customers/{id}` | Yes (only if not in local replica) | 35 ms | 1 | yes | Conservative default profile, `PROFILE_UNAVAILABLE` |
| Device intelligence | `POST /device-intel/v1/assessments` | Yes | 55 ms | 1 | yes (read-only) | Device treated as unknown, `DEVICE_RISK_UNAVAILABLE` |
| Case management | `POST /cases/v1/cases` + `Idempotency-Key: decisionId` | No (asynchronous) | 5 s | 4 | yes (because of the key) | `integration_failures` row, rethrow for consumer retry, reconciliation job |
| Model-service | `POST /v1/explanations` | No (on demand) | 4 s | 2 | yes | Stored reasons returned, `shapAvailable=false` |

### The template's rules
| Rule | Why |
|---|---|
| **Deadline, not only timeouts**: per-attempt timeout and back-off are cut to the remaining budget | Retries can never exceed the caller's latency budget |
| Retry only idempotent operations and retryable failures (timeouts, connection errors, 5xx, 429) | Retrying a non-idempotent POST can duplicate side effects |
| Exponential back-off with jitter | Avoid synchronised retry storms |
| 4xx never retried; 404 is a business outcome | A bad request stays bad; "not found" is not an outage |
| Circuit breaker ignores 404/4xx | Our own defects and normal "not found" must not open the circuit for everyone |
| Bulkhead (max concurrent calls) | A slow dependency cannot absorb all threads |
| Contract validation on 2xx (parse + predicate) | A 200 with a maintenance HTML page is a failure, not data |
| Correlation ID and idempotency key headers | End-to-end tracing; safe retries |
| Timer `integration.client.requests{integration,operation,outcome}` + circuit-state gauge | Every dependency is observable |

### Hot path vs asynchronous
Synchronous lookups get tens of milliseconds and **no retries** — a second attempt would not fit in the
budget, and a retry storm would amplify a vendor incident. Asynchronous calls (cases, explanations) get
seconds and retries, because nobody is waiting on a payment terminal.

### Cold start (journal J-13)
The first requests after startup exceeded the 55 ms device-risk budget (new TCP connection, JIT) and the
25 ms model budget (lazy model load). `WarmUpRunner` now loads models, runs dummy inferences and opens
connections **before** readiness reports ready.

## 4. Simulators (local only)

`downstream-simulators` (port 8090) implements the three customer systems, deterministically, with
fault injection per system:

```bash
curl -X POST localhost:8090/admin/faults/device-intel -H 'Content-Type: application/json' \
     -d '{"latencyMs":300,"errorRate":0,"errorStatus":0,"malformed":false}'
curl -X DELETE localhost:8090/admin/faults
```

The device-intel simulator flags IPs from a threat feed exported by the workbench that covers only 40% of
fraudster IPs — real feeds are incomplete.

## 5. Tests
| Test | Covers |
|---|---|
| `IntegrationClientTest` (8) | Retry with success, no retry for non-idempotent, deadline cut-off, typed 404/4xx, contract violations, circuit opens and fails fast, 404 does not open it, header propagation, metrics |
| `OutboundIntegrationTest` (8) | CRM fetch + cache into replica, CRM failure degrades, compromised IP reason + correlation header, slow vendor cut at budget, case creation with 2 transient failures then success and idempotent duplicate, permanent 4xx recorded for support, analyst resolution + label + stale If-Match, explanation with and without model-service |
| `OpenApiContractTest` (3) | Live responses satisfy the published spec; published enums equal code enums |
| `SimulatorsTest` (4) | Deterministic CRM, idempotent case creation, threat feed, targeted fault injection |

## 6. Limitations
* No mTLS/OAuth2 locally; no rate limiting per client.
* The Kafka consumer that turns REVIEW decisions into cases is Stage 6; until then the reconciliation job
  and direct service calls cover case creation.
* Contract testing is provider-side against the published spec; consumer-driven contracts (e.g. Pact) are roadmap.

## 7. Key takeaways
* "Timeouts come from the latency budget. On the payment path the device vendor gets 55 ms and one attempt;
  case creation is asynchronous, so it gets 5 seconds and retries."
* "POST isn't idempotent, but it can be made so: case creation sends the decision ID as the idempotency key,
  so retrying after a timeout can't open two cases."
* "A vendor outage must not decline genuine customers: device risk unavailable means 'unknown', not 'risky'."
* "I found a cold-start problem in tests: the first calls after startup blew their budgets. The fix is a
  warm-up before readiness — the same thing you'd want in a Kubernetes rolling deployment."
