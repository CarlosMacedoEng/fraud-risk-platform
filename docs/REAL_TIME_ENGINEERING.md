# Real-Time Engineering

> Design as implemented, with measurements from [perf/README.md](../perf/README.md) (laptop, 2 vCPU
> container, synthetic load). Targets are labelled as targets.

## 1. Latency budget
Per request (ARCHITECTURE.md §4.1): profile 40 ms, feature store 30 ms, graph 20 ms, device risk 55–60 ms,
model 25 ms, strategy ~1 ms, persistence ~5 ms; 300 ms hard deadline. The enrichment calls run in parallel,
so the critical path is the slowest one, not the sum.

Measured on a warm JVM at 150 TPS: **server p50 11 ms, p95 17 ms, p99 63 ms**; decision logic alone
(`processingTimeMs`, excl. persistence) p50 6 ms, p95 9 ms. CPU cost ≈ **11.7 CPU-ms per request**
(model inference ≈ 2 ms of it, mostly the 150-tree Isolation Forest).

## 2. Throughput and capacity
| Quantity | Value (2 vCPU) | How it was obtained |
|---|---|---|
| CPU per request | 11.7 ms | calibration run, 30 rps, `process_cpu_usage` |
| Theoretical ceiling | ~170 rps at 100% CPU | 2,000 CPU-ms/s ÷ 11.7 |
| Observed knee | ~300 rps offered | stress test (above ~170 rps an increasing share of decisions is degraded, which skips work). **Later explained in part by J-26:** Redis pipelines opened a TCP connection each, exhausting ephemeral ports at ~157 rps. Fixed; the knee has not been re-measured |
| SLO-compliant sustained load | 150 TPS at 83% peak CPU (baseline-06); after the J-26 fix: p95 15.2 / p99 40.1 ms at 64% peak CPU (baseline-08) | perf/README.md |

Capacity planning rule used: keep steady-state CPU ≤ ~65–70% so queueing does not explode (at 88%
utilisation the cold runs showed exactly that). For Aldermoor's assumed 250 TPS peak with N+1 redundancy:
3 instances × 2 vCPU (or 2 × 4 vCPU) plus headroom for deployments — to be validated with the customer's
real traffic mix, since decision mix drives cost (REVIEW → case creation, fallbacks).

## 3. Concurrency model
| Work | Execution | Why |
|---|---|---|
| HTTP request handling | virtual threads (Tomcat) | cheap blocking I/O; no thread-pool sizing for I/O waits |
| Enrichment (profile, feature state, graph, device risk) | virtual thread per call + `orTimeout` budget | parallel I/O; each bounded individually |
| Model inference (CPU-bound) | fixed pool of 4 platform threads, bounded queue (bulkhead) | CPU work must not scale with request concurrency; saturation → rules-only fallback, not queueing |
| Outbox relay, consumers, reconciliation | scheduled threads / Kafka listener containers | off the request path |

Snapshot under load: 28 virtual threads in flight, all parked on Redis responses (no pinning) — see the
virtual-thread dump in `perf/results/baseline-06-cb-window-warm/jvm/`.

## 4. Backpressure and load shedding
Virtual threads remove the natural limit a thread pool used to provide. Without a limit, the stress test
showed **congestion collapse**: beyond ~300 rps every request queued on the 20-connection DB pool, timed
out after 1 s and failed; throughput fell *below* capacity (85–157/s, p95 2.4 s, 53% errors).

`AdmissionControlFilter` caps in-flight scoring requests (32, configurable) with a 20 ms queue timeout.
Excess requests get **`503 OVERLOADED` + `Retry-After: 1` within milliseconds**; the payment gateway applies
its stand-in policy (e.g. approve low-value card-present, hold transfers). At 600 rps offered, admitted
requests kept p95 ≈ 200 ms server-side and connection waits dropped from 1,412 to 14.

Choosing the limit: Little's law — at the knee (~300 rps) × ~25–100 ms in-flight time ≈ 8–30 concurrent
requests; 32 also stays above the DB pool (20) so the pool, not the filter, is the steady-state limiter.
Roadmap: adaptive concurrency limits (gradient/AIMD) instead of a static number.

## 5. Resilience patterns and what the measurements taught
| Pattern | Where | Tuning lesson (measured) |
|---|---|---|
| Timeouts / budgets | every dependency | Budgets come from the caller's latency budget; hot-path calls get one attempt |
| Circuit breaker | Redis (features, graph), each HTTP integration | Window must cover enough traffic: a 20-call window (~0.13 s) turned one GC pause into 10 s of degraded decisions (11% → 0.54% after switching to a 10 s window). Slow-call threshold must exceed CPU jitter (50 → 250 ms) |
| Bounded fallback | PostgreSQL velocity fallback (4 concurrent) | Unbounded, it turned a Redis slowdown into DB-pool exhaustion |
| Bulkhead | inference pool; HTTP client max concurrent calls | CPU work isolated from I/O concurrency |
| Idempotency | API (keys), events (eventId), case creation (external key) | makes retries at every layer safe |
| Graceful degradation | every signal | always flagged in `degradedModes`, reasons and metrics (hidden degradation masked two bugs, J-07/J-08) |
| Load shedding | admission control | preferable to collapse; gateway owns the stand-in decision |

A **slow** dependency is harder than a dead one: in the degradation test a vendor slowed by 300 ms caused
893 shed requests in ~90 s (timed-out calls abandon connections, reconnects consume CPU) while a frozen
Redis caused none. Roadmap: cap vendor client connections, open the vendor circuit on slow calls faster,
and cancel in-flight calls when the budget expires.

## 6. Caching
| Data | Where | Freshness |
|---|---|---|
| Velocity / seen sets | Redis (TTL by window) | real time (updated after each commit) |
| Graph risk | Redis hashes | snapshot refresh (daily locally; minutes as a production target) |
| Customer profile | PostgreSQL replica (files + events); CRM API only on miss | daily file + events |
| Compiled strategies | in-process, per deployment row version | refresh on `ConfigurationChanged` or ≤ 15 s |
| Models | in-process ONNX sessions | loaded once per version, SHA-256 verified |

## 7. Warm-up and deployments
A cold JVM failed the SLO for minutes (C2 compiler at 24–30% CPU). Implemented: `WarmUpRunner` (model
loading, dummy inferences, connection warm-up) before readiness. Not sufficient on its own — measured runs
needed minutes of production-like load. For Kubernetes: `maxSurge`/slow-start traffic ramp, keep
`minReadySeconds`, scale out before peak, consider CDS/AOT caching or CRaC (roadmap).

## 8. Scalability
Stateless instances (state in PostgreSQL/Redis/Kafka) scale horizontally behind the gateway; the outbox relay
is multi-instance safe (`SKIP LOCKED`); consumers scale with partitions (3 locally). Next bottlenecks with more
instances: PostgreSQL write IOPS (4–5 writes/decision: transaction, decision, idempotency, 2–3 outbox rows)
→ partition `risk_decisions`/`outbox_events` by time, CDC instead of polling, read replicas for reporting.

## 9. Key takeaways
* "My first load test failed — p99 1.5 s. The cause was a fallback that cost more than the thing it replaced.
  Bounding it, sizing the breaker window to traffic and adding admission control got p99 to 68 ms at the same load."
* "Virtual threads removed my accidental concurrency limit, so overload became collapse. Admission control
  turns overload into fast 503s the gateway can act on."
* "The same build passes or fails depending on JIT warmth — that changes how you do rolling deployments."
