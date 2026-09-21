# ADR-006: Explicit SQL (Spring JdbcClient) instead of JPA in the decision service

- **Status:** Accepted (Stage 0)
- **Context:** The decision service writes a few well-known rows per request under tight latency, and support
  engineers need to reason about the exact SQL being executed (explain plans, locks, indexes).
- **Decision:** Use Spring `JdbcClient` with explicit SQL, Flyway for migrations, HikariCP for pooling.
- **Consequences:**
  - (+) No hidden lazy loading or N+1 queries; SQL in the code is the SQL in `pg_stat_statements`.
  - (+) Easier troubleshooting and explain-plan documentation.
  - (−) More boilerplate for mapping; no automatic dirty checking.
