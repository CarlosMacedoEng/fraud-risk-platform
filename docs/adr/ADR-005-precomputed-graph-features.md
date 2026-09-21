# ADR-005: Precomputed graph risk, looked up online

- **Status:** Accepted (Stage 0)
- **Context:** Fraud rings share devices, IPs, cards and beneficiaries. Graph traversal per request is too slow
  and unpredictable for the hot path without a dedicated graph database.
- **Decision:** A near-real-time batch job (Python, NetworkX) builds an entity graph (account, card, device,
  IP, merchant, beneficiary) from recent transactions and labels, computes per-entity features
  (connected-component size, fraud-labelled neighbours, shared-device degree, ring score) and writes them to
  Redis with a version and timestamp. The decision service looks them up by key.
- **Consequences:**
  - (+) O(1) lookup on the hot path; graph logic testable offline.
  - (−) Staleness: a brand-new ring is only visible after the next job run (minutes). Documented limitation.
  - (−) NetworkX is single-machine; production would use a graph database or streaming graph features.
