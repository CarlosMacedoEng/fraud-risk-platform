# Performance Test Plan / Report — Template

> Based on how the tests in `perf/` were run and on what went wrong there (J-19, J-22, J-26). Copy per test.

## 1. Question
What decision does this test support? (e.g. "Can one pod sustain 150 TPS within p95 ≤ 100 ms / p99 ≤ 250 ms?")

## 2. Environment (record exactly — results without this are meaningless)
| Item | Value |
|---|---|
| Git commit / image tag | |
| CPU / memory limits of the service under test | |
| JVM version and flags (`JAVA_TOOL_OPTIONS`) | |
| **JVM warmth** (fresh start / warmed by …) | |
| Dependencies (DB size, Redis keys, broker) and their resources | |
| Load generator location and resources | |
| Other load on the machine | |

## 3. Workload
| Item | Value |
|---|---|
| Arrival model | **open** (constant / ramping arrival rate); closed models hide latency (coordinated omission) |
| Rates and durations (warm-up, sustained, ramp) | |
| Data pool: size, entity cardinality, realism caveats | J-28: a small pool inflates velocity features and changes the decision mix |
| Request mix (tenants, channels, types) | |

## 4. Success criteria (decided before the run)
Latency percentiles (client- and server-side), error rate, degraded share, resource ceilings (CPU, pool waits,
GC pause share), no data loss (responses = persisted rows = published events).

## 5. What to capture during the run
Client summary (k6), server histograms (Prometheus), CPU per thread + hot methods + allocation (JFR 60 s), JSON
thread dump (virtual threads), heap info, GC log, pool metrics, DB slow-query log, and **OS-level resources that
can run out** (ephemeral ports / TIME_WAIT, file descriptors). J-26 was invisible in application metrics.

## 6. Results
| Run | Change under test | p50 / p95 / p99 | Errors | Degraded | CPU peak | Verdict |
|---|---|---|---|---|---|---|
| | | | | | | |

Keep failed runs. Compare runs only at equal JVM warmth and equal data state.

## 7. Analysis
Bottleneck, evidence, hypotheses tested and rejected, cost per request (CPU-ms), capacity estimate with headroom.

## 8. Limitations
Why these numbers may not transfer to the target environment.
