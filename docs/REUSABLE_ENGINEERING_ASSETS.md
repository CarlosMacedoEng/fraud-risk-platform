# Reusable Engineering Assets

> Assets meant to be reused in the next customer implementation. "Code" assets are working, tested parts of
> this repository; "Template" assets are documents in [templates/](templates/) or [customer/](customer/).
> Each entry says when to use it and what it already learned from this project.

| # | Asset | Type | Location | Tested / used |
|---|---|---|---|---|
| 1 | Java integration client | Code | `platform-commons/.../integration/IntegrationClient.java` | `IntegrationClientTest` (8 tests), used by 4 outbound clients |
| 2 | REST error-handling pattern | Code | `ErrorCode`, `PlatformException`, `ApiExceptionHandler` | API + contract tests |
| 3 | Event envelope + schema template | Code + template | `EventEnvelope`, `docs/events/*.schema.json`, [templates/event-schema-template.json](templates/event-schema-template.json) | schema tests (`EventSchemas`) |
| 4 | File-ingestion template | Code | `file-adapter` (`FileSpec`, `FieldSpec`, `FileProcessor`) | 13 tests; TS-09/10 |
| 5 | Database migration template | Template | [templates/database-migration-template.sql](templates/database-migration-template.sql) | pattern rehearsed in TS-17 and release 2.0 |
| 6 | Online backfill + reconciliation pattern | Code | `DecisionChannelBackfillService` | `ReleaseMigrationIntegrationTest`, rehearsal on 600k rows |
| 7 | Risk-rule template | Template | [templates/risk-rule-template.json](templates/risk-rule-template.json) | compiler operators verified |
| 8 | Configuration-versioning pattern | Code + doc | strategy lifecycle (`StrategyAdminService`, `strategy_deployments`) | governance tests, TS-13/14 |
| 9 | Observability checklist | Template | [templates/observability-checklist.md](templates/observability-checklist.md) | derived from J-07, J-16, J-25, J-29 |
| 10 | Performance-test template + harness | Template + code | [templates/performance-test-template.md](templates/performance-test-template.md), `perf/` | 15 recorded runs |
| 11 | Production-readiness checklist | Template | [customer/production-readiness-checklist.md](customer/production-readiness-checklist.md) | – |
| 12 | Incident runbook template | Template | [templates/incident-runbook-template.md](templates/incident-runbook-template.md) | structure of the 18 playbook entries |
| 13 | Architecture-review checklist | Template | [customer/architecture-review-agenda.md](customer/architecture-review-agenda.md) | – |
| 14 | Integration-readiness checklist | Template | [customer/integration-readiness-checklist.md](customer/integration-readiness-checklist.md) | – |
| 15 | Fault-injection lab | Code | `troubleshooting-lab/`, `lab` profile, simulator faults | 18 incidents reproduced |
| 16 | Kubernetes base + overlays | Code | `deploy/k8s/` | deployed to kind |

## 1. Java integration client (`IntegrationClient`)

**Use when** calling any customer or vendor HTTP API.
**What it gives you:** per-operation idempotency flag (retries only for idempotent operations or when an
idempotency key is sent), connect / attempt / overall deadline timeouts, exponential back-off inside the
deadline, circuit breaker and bulkhead per endpoint, response contract check (`Predicate<T>`), error
classification (`TIMEOUT`, `CIRCUIT_OPEN`, `SERVER_ERROR`, `CLIENT_ERROR`, `CONTRACT_VIOLATION`, …) with
`retryable()`, correlation-ID propagation and Micrometer metrics by outcome.

```java
var client = new IntegrationClient(Settings.defaults("device-risk", URI.create(baseUrl)), json, meters);
DeviceRisk r = client.get(new Operation("lookup", true), "/device-intel/v1/devices/" + id,
        DeviceRisk.class, x -> x.deviceId() != null, Duration.ofMillis(55));
```
**Lessons built in:** the hot path gets one attempt inside its budget (no retries), the asynchronous path gets
retries with an idempotency key; a slow vendor is worse than a dead one (J-23), so the breaker's slow-call
threshold matters.

## 2. REST error-handling pattern
One error contract (RFC 7807 problem details plus `code` and `correlationId`) for every error; a closed
`ErrorCode` enum with HTTP status and "retryable" meaning. Only **transient** data-access failures map to 503
(J-12: a SQL defect reported as "database unavailable, retry" cost more time than the bug). Validation errors are
mapped field by field; framework defaults are removed so there is only one format (J-10).

## 3. Event envelope and schema governance
Envelope with `eventId` (idempotency), `eventType` + `eventVersion`, `tenantId`, `occurredAt`, `correlationId`.
Additive changes only within a version; breaking changes mean a new version published in parallel. Partition key
= the entity whose order matters. Delivered through the transactional outbox; consumers de-duplicate on
`eventId` and have a per-group DLT with redrive (MESSAGING_AND_EVENTS.md).

## 4. File-ingestion template
Add a file type by adding a `FileSpec` entry (format, source system, ordered fields with types and required
flags, natural key, maximum invalid ratio). The processor provides: name contract, `.done` marker, duplicate
detection by content hash and by file identity, header check (schema mismatch → reject the whole file), record
validation with quarantine and line numbers, error-ratio policy (systemic problem → reject), processing report,
events with deterministic IDs, reconciliation.

## 5–6. Migrations, backfill and reconciliation
Expand → backfill job → reconcile → contract, each in its own release step. The backfill pattern: keyset cursor
over an existing index, short transaction per batch, throttle, idempotent `WHERE col IS NULL`, reconciliation
query with its own timeout (J-36), single-run guard. **Throttle by effect:** the rehearsal showed a fast
backfill raising scoring p99 from 44 ms to 1 s (J-38).

## 7–8. Rules and configuration versioning
Strategies are versioned documents with a lifecycle DRAFT → VALIDATED → (deployed) → RETIRED: validation by the
compiler, four-eyes approval, promotion order dev → staging → prod, canary percentage by customer bucket, rollback
to the previous version with optimistic locking (`If-Match`), every step in `audit_events` and published as a
`ConfigurationChanged` event so all instances refresh. Emergency path: derive + emergency flag + mandatory reason.
Compare versions with **paired simulation** on the same transactions (TS-13 lesson), not before/after.

## 9–14. Checklists and templates
See the table. They were written after the mistakes they prevent; each item that came from a specific finding
cites it (J-nn / TS-nn), so the reason survives when the author is not in the room.

## 15. Fault-injection lab
Lab-only fault points in the decision service (`lab` profile: model latency, feature-store latency,
persistence delay, CPU burn, lock contention, heap leak) and per-system faults in the downstream simulators
(latency, error rate, malformed bodies). Every incident script captures its own evidence. Reuse: rehearse
runbooks with a new customer's operations team in staging before go-live.

## 16. Kubernetes manifests
Base without stateful services, dev overlay for kind, prod overlay for managed services. Settings are
justified by measurements (AWS_AND_KUBERNETES.md §2). Includes the three pitfalls found on the first real
deployment (service links, read-only FS, bind-mount assumptions, J-34).
