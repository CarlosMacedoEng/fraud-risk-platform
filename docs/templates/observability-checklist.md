# Observability Checklist (per service / integration)

## Logs
- [ ] JSON, one event per line; `tenantId`, `correlationId`, `clientId` in every line (MDC)
- [ ] No PAN, no secrets, no full payloads at INFO; identifiers only
- [ ] A catch-and-continue in a loop has a **metric**, not only a log line (J-16)
- [ ] Error logs include the cause chain; the API returns the correlation ID to the caller

## Metrics
- [ ] Golden signals per endpoint: rate, errors, latency histogram (p50/p95/p99), saturation
- [ ] Every dependency: calls by outcome (success / timeout / error / circuit_open), latency, breaker state
- [ ] Degradation is observable: degraded decisions **by mode** (J-07: graceful degradation hid a bug)
- [ ] Pools: DB active/pending, Redis pool, executor queues, admission in-flight/rejected
- [ ] Messaging: outbox age/backlog, consumer lag **and assigned partitions**, DLT publications (J-25)
- [ ] Business outcomes: decision mix, REVIEW → case, files accepted/rejected
- [ ] Meters cached on hot paths (meter lookup showed up in profiling)

## Alerts
- [ ] Each alert has severity, summary and a runbook link; each one fired once in a test environment
- [ ] Alerts on outcomes, not only internals (`CaseCreationStalled`, `ReviewShareDrift`)
- [ ] Rules validated with `promtool check rules`

## Health
- [ ] Liveness = process responsive only; readiness = can decide correctly (DB, Redis, models, strategies) (J-29)
- [ ] Startup probe covers warm-up

## Diagnostics
- [ ] JDK tools available (or an ephemeral debug container): `jcmd`, JSON thread dumps, JFR
- [ ] Heap dump on OOM to a volume that survives (or is shipped)
- [ ] Slow-query log / `pg_stat_statements` enabled
