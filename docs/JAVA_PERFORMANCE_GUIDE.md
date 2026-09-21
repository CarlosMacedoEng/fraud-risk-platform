# Java Performance Guide

> Practical guide built from the analysis actually performed on `decision-service` (Java 21, Spring Boot 4,
> G1, virtual threads) during the Stage 8 load tests. Evidence files: `perf/results/*/jvm/`.
> Environment: laptop, 2 vCPU / 1.5 GB container — numbers are illustrative of the method, not of production.

## 1. Workflow
1. **Reproduce with a known load** (k6 open model, fixed rate) — never profile "whatever traffic there is".
2. **Look at the system level first**: CPU %, GC time, pool waits, dependency latency, degraded modes (Grafana).
3. **Form one hypothesis**, capture the right artefact (profile, dump, histogram), change one thing.
4. **Re-measure at equal warmth** and keep the change only if it measurably helps (J-19: my "optimisation" didn't — reverted).

## 2. Command cookbook (inside the container, PID 1)

```bash
docker exec -it fraud-platform-decision-service-1 bash    # JDK image includes jcmd/jfr
jcmd 1 VM.flags                                  # effective flags (container-derived heap size etc.)
jcmd 1 Thread.print > threads.txt                # platform threads only!
jcmd 1 Thread.dump_to_file -format=json /dumps/threads.json   # includes virtual threads
jcmd 1 GC.heap_info                              # heap regions, occupancy
jcmd 1 GC.class_histogram | head -30             # live objects by class (forces a full GC — use sparingly)
jcmd 1 GC.heap_dump /dumps/heap.hprof            # full dump for Eclipse MAT (stop-the-world, large file)
jcmd 1 JFR.start name=p duration=60s settings=profile filename=/dumps/p.jfr
jfr view hot-methods /dumps/p.jfr                # CPU samples by method
jfr view allocation-by-class /dumps/p.jfr        # allocation pressure
jfr view gc /dumps/p.jfr                         # every collection with pause
jfr view thread-cpu-load /dumps/p.jfr            # CPU per thread (JIT threads included)
jfr print --events jdk.VirtualThreadPinned /dumps/p.jfr   # carrier pinning (Java 21)
```

On Git Bash for Windows use `MSYS_NO_PATHCONV=1`, otherwise `/dumps/...` is silently rewritten to a Windows
path and JFR/jcmd write nowhere (J-24).

## 3. Thread dumps
* **Platform threads** (`Thread.print`): 77 threads under load — Tomcat poller, Lettuce event loops, Kafka
  consumers, the 4 `inference-*` workers, schedulers. Useful for deadlocks (`Found one Java-level deadlock`),
  blocked monitors, pool starvation (many threads `WAITING` in `HikariPool.getConnection` / `ConcurrentBag.borrow`).
* **Virtual threads are not in `Thread.print`.** All HTTP request handling runs on virtual threads, so a classic
  dump of a slow service can look idle. Use the JSON dump: under 150 TPS it contained 28 virtual threads, all
  parked in `VirtualThread.park` inside `RedisFeatureStore.load` / `RedisGraphFeatureStore.lookup` — normal
  I/O waits.
* Take **3 dumps ~5 s apart**: a thread stuck in the same frame in all of them is the lead.
* Pinning (Java 21): a virtual thread blocking inside `synchronized` pins its carrier. JFR recorded
  **0 `VirtualThreadPinned` and 0 `JavaMonitorEnter` events** in 60 s at 150 TPS. The only `synchronized`
  block on a request path is the in-memory feature store (tests only). JDK 24+ removes most pinning (JEP 491).

## 4. CPU profiling (JFR)
Findings, in order of impact:
1. **JIT compilation**: right after start, `C2 CompilerThread0` used **24–30%** of the container's CPU for
   minutes. The same build failed the SLO cold and passed warm (J-22).
2. Warm profile is **flat** — no method above ~3% of samples (map lookups, Jackson, Micrometer tags,
   Spring Boot nested-jar URL lookups). Flat profiles mean *cost per request*, not a hotspot: the options are
   less work per request or more CPU.
3. Model inference ≈ 2 ms wall per request on the dedicated pool (Isolation Forest dominates).
4. Allocation pressure: `ThreadLocalMap$Entry` (MDC copies on enrichment threads), Micrometer
   `KeyValue[]` (observations), regex `Matcher` (Bean Validation `@Pattern`). Meter instances are now cached;
   the rest is acceptable at ~90 MB/s allocation.

CPU cost per request (the capacity number): `process_cpu_usage × vCPUs ÷ rps` on a warm JVM at low load
= **11.7 CPU-ms** → ceiling ≈ 170 rps per 2 vCPU.

## 5. Garbage collection
G1 with `-XX:MaxGCPauseMillis=50`, heap = 70% of container memory (`-XX:MaxRAMPercentage=70`).
Measured at 150 TPS (`jfr view gc`, `gc.log`):

| Metric | Value |
|---|---|
| Young collections | every ~1.3 s, 180 MB → 65 MB, **pause 5–7 ms** |
| Old/mixed cycles | a few per minute, pauses 9–19 ms |
| Total pause | 0.94 s in 247 s (**0.4%** of time) |
| Live set after GC | 62–73 MB (heap committed ~200 MB of ~1 GB max) |

GC is not the bottleneck; young pauses (≈ 6 ms) contribute to p99 but not to failures. If it were: first
reduce allocation (profile `allocation-by-class`), then size young gen / consider ZGC for sub-ms pauses at
the cost of CPU. Always keep GC logs on (`-Xlog:gc*` with rotation) — they are cheap and invaluable post-incident.

## 6. Heap analysis
* `GC.class_histogram` under load: `byte[]`, `String`, `ConcurrentHashMap$Node` at the top — expected
  (JSON, Kafka buffers, caches). Watch for **growth between two histograms minutes apart** (leak signature).
* Memory leak checklist: unbounded caches (`computeIfAbsent` maps keyed by user data!), ThreadLocals on
  pooled threads, listeners never removed, metrics with high-cardinality tags (a transaction ID as a tag
  creates one meter per request — forbidden by the standards).
* `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps` + `-XX:+ExitOnOutOfMemoryError`: the container
  dies (orchestrator restarts it) and the dump survives on the volume for Eclipse MAT
  (dominator tree → "who retains the most").
* Metaspace ~110 MB (Spring Boot + ONNX Runtime + Kafka) — include it when sizing container memory:
  container = heap + metaspace + thread stacks + direct buffers + native (ONNX Runtime allocates natively).

## 7. JVM memory settings in containers
| Setting | Value | Why |
|---|---|---|
| `-XX:MaxRAMPercentage=70` | ~1 GB heap in 1.5 GB | leave room for metaspace, native (ONNX), stacks |
| `-XX:InitialRAMPercentage=50` | | avoid early heap-resize churn |
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=50` | | balanced default for request/response services |
| `-XX:+ExitOnOutOfMemoryError` | | fail fast; half-alive JVMs are worse than restarts |
| Never `-Xmx` equal to the container limit | | the kernel OOM-kills the process (exit 137) with no Java-level diagnostics |

## 8. Pools and executors
| Pool | Size | Sizing logic | Observed |
|---|---|---|---|
| Hikari (decision-service) | 20, `connectionTimeout` 1 s | ~ cores × 2-4 for short transactions; the DB, not the app, bounds useful concurrency | 0 pending when healthy; **161–1,412 pending** during the fallback storm / collapse |
| Inference | 4 platform threads, queue 200, AbortPolicy | CPU-bound: ≈ vCPUs × 2; reject rather than queue | ~4.5% CPU each at 150 TPS |
| Enrichment | virtual thread per call | I/O-bound | 28 in flight under load |
| Admission control | 32 permits | Little's law around the knee; ≥ DB pool | protects against collapse |

Hikari metrics to alert on: `hikaricp_connections_pending > 0` sustained, `hikaricp_connections_usage_seconds`
p99 (hold time), `hikaricp_connections_timeout_total`. Long hold time with fast queries (confirmed with
`pg_stat_statements`: all statements < 1 ms mean) means **the app is slow while holding the connection** —
CPU starvation or remote calls inside transactions.

## 9. Blocking I/O, sync vs async
* The service is written in **blocking style on virtual threads**: simple code, stack traces that make sense,
  timeouts via `orTimeout`. Reactive code would reduce threads but not CPU cost per request.
* Never call a remote system while holding a DB transaction/connection (case creation calls the external API
  outside the transaction; the outbox relay holds a connection only while waiting for Kafka acks — bounded).
* Budgets: `CompletableFuture.orTimeout` stops *waiting*, it does not stop the *work*. A timed-out HTTP call
  may keep its connection busy — the source of the slow-vendor effect in the degradation test (J-23).

## 10. Latency percentiles and throughput
* Report p50/p95/p99 **and** max from an open-model load generator, both client-side (k6) and server-side
  (Micrometer histogram). Averages hide exactly the tail we fought.
* Throughput is only meaningful with its latency: "300 rps" was the knee; "150 rps at p99 68 ms" was the SLO point (p99 40 ms after the J-26 connection-pool fix).
* Utilisation vs latency is non-linear (queueing): at ~88% CPU the tail exploded; plan for ≤ 65–70%.

## 11. Backpressure
Bounded everything: inference queue, fallback semaphore, HTTP client bulkheads, admission control at the
edge, Kafka consumers pull at their own pace, the outbox absorbs broker outages. Unbounded concurrency
(virtual threads) + a bounded resource (DB pool) = collapse — measured in `stress-01`.

## 12. Performance test template
See `perf/scoring.js` and [`docs/templates/performance-test-template.md`](templates/performance-test-template.md): scenario, arrival model,
warm-up policy, environment capture, success thresholds, server-side metrics, JVM artefacts, result table
including failed runs.
