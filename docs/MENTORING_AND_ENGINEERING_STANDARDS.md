# Mentoring and Engineering Standards

> Short internal guide for engineers joining this implementation. Every rule points at the place in the
> codebase where it is applied, or at the journal entry that explains why it exists. Rules without a reason
> tend to be ignored; rules with a story tend to stick.

## 1. Coding standards (Java 21, Spring Boot 4)
* Constructor injection, final fields, no field injection. Records for DTOs and value objects.
* Explicit SQL with `JdbcClient` / `JdbcTemplate` (ADR-006). Always type ambiguous parameters (`?::timestamptz`)
  — untyped parameters failed silently twice (J-08, J-12).
* Watch auto-unboxing in ternaries mixing `long` and `Long` (J-09).
* Hot path: no blocking call without a budget; no `synchronized` around I/O (pins virtual threads, TS-05);
  cache Micrometer meters instead of looking them up per request.
* Fail fast at the edges (validation, timeouts), degrade explicitly in the middle (degraded modes in the
  response), never swallow exceptions silently.

## 2. Package structure
`api` (controllers, filters, error mapping) → `application` (use cases) → `domain` (no framework types) ←
`strategy`, `inference`, `features`, `persistence`, `integration`, `messaging` (adapters) — plus `observability`,
`config`, and `lab` (fault injection, active only with the `lab` profile). Dependencies point inwards; adapters
implement ports defined by the application layer (e.g. `DeviceRiskClient` ← `HttpDeviceRiskClient`).

## 3. Testing expectations
| Level | Expectation | Example |
|---|---|---|
| Unit | Pure logic, fast, no Spring context | `StrategyEngineTest`, `ModelsReadinessTest` |
| Integration | Real PostgreSQL/Redis/Kafka via Testcontainers; WireMock for HTTP | `DecisionApiIntegrationTest`, `MessagingIntegrationTest` |
| Contract | OpenAPI and event schemas checked in tests | `OpenApiContractTest`, `EventSchemas` |
| Parity | Python ↔ Java feature and score parity on golden data | `FeatureParityTest`, `OnnxGoldenScoresTest` |
| Failure | Every fallback path has its own test; happy-path tests assert **no** degradation | J-07, J-08 |
| Performance | CPU-ms per request per release (calibration scenario) | `perf/` |
Rules: a flaky test is a defect in the test, not a reason to re-run (J-39); clear stale reports and build through
the reactor (J-14); every bug fix starts with a failing test.

## 4. Logging standards
JSON logs; MDC carries `tenantId`, `correlationId`, `clientId`. INFO for state changes, WARN for handled degradation,
ERROR for defects. Never log PAN, secrets or full payloads. **Rate-limit repetitive warnings**: during TS-06 every
request logged `device risk unavailable: CIRCUIT_OPEN` — noise that hides the first, useful line (see the review
comments below). Counters for repetitive events, a single log line on state change.

## 5. API versioning and backward compatibility
* Version in the path (`/v1`). Within a version: only additive changes (new optional request fields, new
  response fields); clients must ignore unknown fields.
* Breaking change ⇒ `/v2` served in parallel, with a deprecation date agreed with each customer.
* Error `code` values are part of the contract; adding is fine, changing meaning is not.
* Idempotency semantics (`Idempotency-Key` + client) never change within a version.

## 6. Event-schema governance
Envelope + versioned payload schema in `docs/events/`; additive-only within a version; breaking change = new
event version published in parallel; consumers idempotent on `eventId`; partition key documented per event; DLT
per consumer group. Template: [templates/event-schema-template.json](templates/event-schema-template.json).

## 7. Database migration rules
1. Never edit an applied migration. 2. Expand → backfill job → contract across releases. 3. `lock_timeout` on
DDL; `CREATE INDEX CONCURRENTLY` for big tables. 4. Test on production-sized data and **measure the effect on the
live path**, not only the duration (J-38). 5. Old version must run on the new schema until the contract step
(verified in the rollback rehearsal, J-35). Template:
[templates/database-migration-template.sql](templates/database-migration-template.sql).

## 8. Security expectations
Authentication on every endpoint; admin, approver, analyst and scoring roles separated; four-eyes for strategy
changes (enforced in code). Secrets only from a secret manager / environment; only SHA-256 hashes of API keys in
configuration. Tokens, never PAN. Least-privilege database users in production. Containers non-root, read-only
root filesystem. Dependencies pinned (`requirements.lock`, Maven BOM).

## 9. Code-review checklist
- [ ] Does it do what the ticket says, and is there a test that proves it (including the failure path)?
- [ ] Timeouts on every remote call; retries only for idempotent operations; behaviour on failure defined?
- [ ] Transaction scope minimal (no remote calls inside, TS-02)?
- [ ] SQL typed, indexed for the query, paginated with keyset?
- [ ] Degradation observable (metric/mode), not silent?
- [ ] Logs useful, not noisy, no sensitive data?
- [ ] Backward compatible (API, events, schema)? If not, versioned?
- [ ] Config documented, with a safe default?
- [ ] Docs/runbook updated if operators must know about it?

## 10. Documentation standards
Every document states what is implemented, what is designed only, and its limitations. Numbers name their
environment and evidence file. Decisions go into ADRs (internal) or DDRs (customer). Corrections are recorded,
not silently overwritten (perf README "Correction after Stage 9").

---

## 11. Mentoring exercise — "Add a hot-path vendor lookup"

**Task for a junior engineer.** The customer licenses an IP-reputation service
(`GET /ip-reputation/v1/ips/{ip}` → `{"ip":"…","score":0-100,"listed":true|false}`). Add it to scoring: a
listed IP adds the existing reason code `COMPROMISED_IP`. The scoring budget for this call is **20 ms**.

**Learning objectives.** Budgets on the hot path; idempotency vs retries; failure semantics that protect
customers; observable degradation; testing failure modes with WireMock; not flooding logs.

**Expected solution (outline).**
```java
public class HttpIpReputationClient implements IpReputationClient {
    static final IntegrationClient.Operation LOOKUP = new IntegrationClient.Operation("ip-reputation", true);
    record Dto(String ip, Integer score, Boolean listed) {
        boolean valid() { return score != null && score >= 0 && score <= 100 && listed != null; }
    }
    private final IntegrationClient client;           // settings: connect 50 ms, attempt 20 ms, breaker, bulkhead
    private final Duration deadline;                  // 20 ms from platform.budgets
    public Optional<IpReputation> lookup(Transaction t) {
        if (t.ipAddress() == null) return Optional.empty();
        try {
            Dto d = client.get(LOOKUP, "/ip-reputation/v1/ips/" + URLEncoder.encode(t.ipAddress(), UTF_8),
                               Dto.class, Dto::valid, deadline);
            return Optional.of(new IpReputation(d.score() / 100.0, d.listed()));
        } catch (IntegrationException e) {
            return Optional.empty();                  // unknown, NOT risky; caller adds a degraded mode + counter
        }
    }
}
```
Plus: a degraded mode (`IP_REPUTATION_UNAVAILABLE`) set by the decision service, a counter by outcome (the client
already records it), a budget entry in configuration, the rule in the strategy (not hard-coded), and tests for
success, listed IP, timeout, 5xx, malformed body, open circuit, and "no IP in the request".

**Common mistakes (seen in reviews of similar work).**
| Mistake | Why it matters |
|---|---|
| Using the default HTTP client timeout (none, or seconds) | One slow vendor stalls every request; admission control then sheds good traffic (J-23) |
| Retrying on the hot path | The retry cannot fit in 20 ms; it only adds load to a struggling vendor |
| Treating "vendor down" as "IP risky" | A vendor outage declines genuine customers |
| `catch (Exception e) { return false; }` without metric or degraded mode | The failure becomes invisible (J-07: exactly how a bug hid behind a fallback) |
| Logging a WARN per failed request | Log flood during an outage (seen in TS-06) |
| Rule hard-coded in Java | Needs a release to change; not versioned or audited |
| Tests only for the happy path | The failure paths are where production incidents live |
| Path built by string concatenation without encoding | IPv6 and malformed input break the URL |

**Review comments (as I would write them).**
1. "The call has no deadline passed — please take it from `platform.budgets` so ops can tune it without a
   release. What happens at p99 today?"
2. "Why `max-attempts: 3`? With a 20 ms budget the second attempt never fits; set 1 and let the breaker handle
   sustained failure."
3. "On failure we return `listed=false` silently. Can we return `Optional.empty()` and mark the decision as
   degraded, so the dashboard shows how many decisions ran without this signal?"
4. "Nice WireMock tests. Could you add the 'slow response' case with `withFixedDelay(100)`? It's the one that
   bites in production."
5. "This WARN will fire on every request while the circuit is open. Can we log on state change and rely on the
   outcome counter otherwise? (Same issue exists in `HttpDeviceRiskClient` — want to fix both?)"

**Follow-up for the mentee.** Run TS-06 with the new vendor slowed down, read the dashboard, write the customer
message for it. Then pair on the fix for the log-flood issue in the existing client.
