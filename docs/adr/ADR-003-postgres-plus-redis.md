# ADR-003: PostgreSQL as system of record, Redis as a degradable feature store

- **Status:** Accepted (Stage 0)
- **Context:** Velocity features (e.g. transactions per card in 10 min / 1 h / 24 h) must be read in a few ms.
  Decisions, strategies and audit data need ACID guarantees and ad-hoc SQL for investigations.
- **Decision:** PostgreSQL holds all records. Redis holds derived, reconstructable data: velocity windows
  (sorted sets with TTL), precomputed graph features, profile cache, idempotency fast path.
- **Consequences:**
  - (+) Redis loss degrades decision quality (flagged in the response) but never loses records.
  - (+) Velocity can be rebuilt from PostgreSQL.
  - (−) Two stores to operate; Redis and PostgreSQL can briefly disagree (documented eventual consistency).
- **Why Redis over MongoDB/DynamoDB here:** the access pattern is key-based counters with time windows and
  TTL, not documents. DynamoDB would be a reasonable AWS-native alternative with higher per-read latency.
