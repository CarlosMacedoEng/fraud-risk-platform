# Messaging and Events

> Implemented in Stage 6. Kafka locally (single broker, KRaft, `apache/kafka-native`); production
> mapping: Amazon MSK (see AWS_AND_KUBERNETES.md). Schemas: [`docs/events/`](events/).

## 1. Event catalogue

| Event | Topic | Key (ordering) | Producer | Consumers (this project) | Purpose |
|---|---|---|---|---|---|
| `TransactionReceived` | `fraud.transactions.v1` | customerId | decision-service, file-adapter (batch) | analytics/DWH (external) | Every scored or ingested transaction |
| `RiskDecisionCreated` | `fraud.decisions.v1` | customerId | decision-service | **case-creator** | Every decision with score, reasons, versions, degraded modes |
| `TransactionApproved` | `fraud.decisions.v1` | customerId | decision-service | notifications (external) | Convenience outcome event |
| `TransactionDeclined` | `fraud.decisions.v1` | customerId | decision-service | card-blocking / notifications (external) | Convenience outcome event |
| `CaseCreated` | `fraud.cases.v1` | customerId | decision-service | case dashboards (external) | Investigation opened |
| `FraudConfirmed` | `fraud.labels.v1` | customerId | decision-service (analyst), file-adapter (chargebacks) | **label-ingestor** | Labels for training/reporting; GENUINE labels too |
| `ConfigurationChanged` | `platform.config.v1` | tenant | decision-service | **config-refresh** (broadcast) | Strategy deployment changed |
| `ModelVersionPromoted` | `platform.config.v1` | tenant | decision-service | model monitoring (external) | Model lifecycle change |

Envelope (`envelope.v1.schema.json`):

```json
{"eventId":"7b9e…","eventType":"RiskDecisionCreated","eventVersion":1,"occurredAt":"2026-04-20T14:03:11.302Z",
 "tenantId":"aldermoor-bank","partitionKey":"ALD-C000123","correlationId":"5d0e…","producer":"decision-service",
 "payload":{"decisionId":"3f1c…","transactionId":"ALD-T000900001","customerId":"ALD-C000123","decision":"REVIEW",
            "riskScore":0.62,"riskLevel":"HIGH","modelProbability":0.41,"modelVersion":"aldermoor-bank-lgbm-1.0.0",
            "strategyVersion":"1.1.0","reasonCodes":["HIGH_MODEL_SCORE","NEW_DEVICE"],"degradedModes":[],
            "amount":6000.00,"channel":"MOBILE"}}
```

Kafka headers duplicate `eventId`, `eventType`, `tenantId`, `X-Correlation-Id` so infrastructure can
route/trace without parsing the body. Broadcast events carry **no card tokens**.

## 2. Delivery guarantees: transactional outbox (ADR-002)

```mermaid
sequenceDiagram
    participant API as Scoring request
    participant DB as PostgreSQL
    participant R as Outbox relay (every 200 ms)
    participant K as Kafka
    participant C as Consumer
    API->>DB: BEGIN; insert transaction, decision, idempotency, outbox rows; COMMIT
    R->>DB: BEGIN; SELECT … WHERE published_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED
    R->>K: send batch (idempotent producer, acks=all)
    K-->>R: acks
    R->>DB: UPDATE published_at = now(); COMMIT
    K->>C: deliver (at least once)
    C->>DB: processed_events check / idempotent effect
```

| Property | Guarantee | How |
|---|---|---|
| No lost events | Event exists iff the business change committed | Same DB transaction; writer refuses to run outside a transaction |
| Broker outage | Scoring unaffected; events wait in the outbox | Relay retries; backlog + oldest-age metrics |
| Duplicates | **Possible** (crash between send and mark; replay; redrive) | Consumers de-duplicate on `eventId` + idempotent effects |
| Producer retries | No duplicates from producer retries | `enable.idempotence=true`, `acks=all` |
| Multiple relay instances | Safe | `FOR UPDATE SKIP LOCKED` |
| Publication latency | ≈ relay interval (200 ms) + broker ack | Tunable; CDC (Debezium) is the lower-latency alternative |

## 3. Ordering assumptions
* Kafka orders per partition; events are keyed by `customerId`, so all events of a customer are ordered.
* The relay sends in `created_at` order; if a send fails, later rows with the **same key in the batch are
  not marked**, so they are retried together and per-key order is preserved.
* `platform.config.v1` has one partition: configuration changes are totally ordered.
* Consumers must not assume ordering **across** customers or topics (e.g. `CaseCreated` may be read before
  the corresponding `RiskDecisionCreated` by an unrelated consumer).

## 4. Consumers, retries and dead-letter topics

| Consumer group | Topic | Effect | Idempotency | Retry policy | DLT |
|---|---|---|---|---|---|
| `case-creator` | `fraud.decisions.v1` | REVIEW → case (REST to case management) | `processed_events` + unique case per decision + external `Idempotency-Key` | 200/400/800 ms, then DLT; 4xx/contract/bad JSON → DLT immediately | `fraud.decisions.v1.case-creator.dlt` |
| `label-ingestor` | `fraud.labels.v1` | Upsert `fraud_labels` (non-analyst sources) | `processed_events` + upsert | same | `fraud.labels.v1.label-ingestor.dlt` |
| `config-refresh-<random>` | `platform.config.v1` | Refresh strategy cache on every instance | naturally idempotent | log and continue (scheduled refresh is the safety net) | none |

DLT records keep the original topic, partition, offset and exception class/message in `kafka_dlt-*` headers.

### Operations
```bash
# Outbox backlog (lag of event publication)
GET  /v1/admin/tenants/{t}/events/outbox
# Peek a DLT without consuming it
GET  /v1/admin/tenants/{t}/events/dlt?topic=fraud.decisions.v1.case-creator.dlt
# Redrive after fixing the root cause (dedicated consumer group: each record redriven once)
POST /v1/admin/tenants/{t}/events/dlt/redrive?topic=fraud.decisions.v1.case-creator.dlt
# Replay events of a time window from the outbox (consumers de-duplicate)
POST /v1/admin/tenants/{t}/events/replay   {"eventType":"RiskDecisionCreated","from":"…","to":"…"}
```

## 5. Replay strategy
| Need | Mechanism |
|---|---|
| Downstream missed events (e.g. their consumer was broken) | Outbox replay for a time window — republishes; idempotent consumers ignore what they already processed |
| A consumer must rebuild state from scratch | Reset that group's offsets (`kafka-consumer-groups --reset-offsets --to-datetime …`) within topic retention (7 days) |
| Failed records after a fix | DLT redrive |
| Beyond Kafka retention | Rebuild from PostgreSQL (decisions, labels are the system of record) |

## 6. Eventual consistency
* Case creation lags the decision by relay interval + consumer processing + case API (normally < 1–2 s).
  The reconciliation job re-dispatches cases stuck in `PENDING_EXTERNAL` for > 30 s.
* Other instances' strategy caches update on `ConfigurationChanged` (sub-second) or, if that event is
  missed, within the 15 s scheduled refresh.
* The feature store is updated after commit (not via events) to keep velocity fresh for the next request.

## 7. Schema governance
* JSON Schema (draft 2020-12) per event and version in `docs/events/`; `EventSchemas` validates published
  events in integration tests.
* **Additive changes** (new optional field, new enum value): same version; consumers must ignore unknown
  fields and tolerate unknown enum values.
* **Breaking changes** (remove/rename/retype, new required field, meaning change): new `eventVersion` and
  new topic (`….v2`), dual-publish until consumers migrate, then retire v1.
* Production roadmap: schema registry (e.g. AWS Glue Schema Registry / Confluent) with compatibility checks in CI.

## 8. Tests (`MessagingIntegrationTest`, real Kafka via Testcontainers)
1. Decision → `RiskDecisionCreated` and `TransactionReceived` published through the outbox, valid against the schemas, keyed by customer, correlation ID preserved, no card token, outbox rows marked published.
2. REVIEW → exactly one case even when the event is delivered twice (one external call, one row), `CaseCreated` published.
3. Poison message → DLT with original topic and exception.
4. Permanent 4xx from case management → DLT without retries → root cause fixed → redrive → case opened.
5. Replay of a window → events republished, consumers stay idempotent (no new external calls).
6. `ModelVersionPromoted` published on model status change and valid against its schema.

## 9. Real issue found (journal J-15)
`delivery.timeout.ms` (30 000) was smaller than `linger.ms + request.timeout.ms`, so the producer failed
to initialise. **No event was lost** — everything stayed in the outbox — but nothing was published and
DLT publication also failed. Lesson: alert on `risk_outbox_oldest_age_seconds`; a silent producer
misconfiguration looks exactly like "no traffic".

## 10. Limitations
* Single broker locally (RF=1); production needs RF=3, `min.insync.replicas=2`.
* DLT operations are cluster-wide (not tenant-filtered); in production restrict to an ops role.
* Outbox polling adds up to ~200 ms latency and DB load; CDC is the scale-out option.
* No exactly-once processing across Kafka and PostgreSQL (by design: idempotent consumers instead).

## 11. Key takeaways
* "I never write to the database and Kafka in the same request path. The event goes into an outbox table in
  the same transaction, and a relay publishes it — so a broker outage can't lose a decision or slow a payment."
* "Delivery is at-least-once, so every consumer de-duplicates on eventId and has idempotent side effects —
  case creation even passes the decision ID as the idempotency key to the external system."
* "Poison messages and permanent rejections go to a per-consumer DLT immediately; transient failures get
  three retries with back-off. After a fix, support can redrive the DLT safely."
