# ADR-002: Transactional outbox for event publication

- **Status:** Accepted (Stage 0)
- **Context:** A decision must be persisted and an event (`RiskDecisionCreated`, etc.) published. Writing to
  PostgreSQL and Kafka in the same request is a dual write: if Kafka is down or the process crashes
  between the two, the database and the event stream disagree.
- **Decision:** Write the decision and one outbox row per event in the **same database transaction**. A relay
  (scheduled poller inside `decision-service`, using `FOR UPDATE SKIP LOCKED`) publishes unpublished rows to
  Kafka and marks them published. Consumers are idempotent (processed-event table keyed by `eventId`).
- **Consequences:**
  - (+) No lost events; Kafka outage does not affect scoring latency.
  - (+) Outbox table doubles as a replay source.
  - (−) At-least-once delivery → duplicates are possible; every consumer must de-duplicate.
  - (−) Publication latency = relay poll interval (target ≤ 1 s); outbox needs housekeeping.
- **Alternatives considered:** Kafka transactions only (doesn't cover the DB write); CDC with Debezium
  (better at scale, more infrastructure — listed in the production roadmap).
