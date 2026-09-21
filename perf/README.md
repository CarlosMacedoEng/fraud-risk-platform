# Performance tests (k6)

> **Measured results, laptop environment.** Docker Desktop on Windows 11, host with 24 logical CPUs and
> 32 GB RAM; the decision-service container is limited to **2 vCPU / 1.5 GB** (heap = 70% of container
> memory), Java 21 (Temurin 21.0.12), G1 GC. All dependencies (PostgreSQL, Redis, Kafka, simulators) run
> on the same host. These numbers describe this environment only — they are not a production capacity claim.

```bash
(cd risk-platform && mvn -B package -DskipTests)
docker compose -f deploy/docker-compose.yml up -d --build && scripts/seed-demo.sh
perf/run.sh calibration      # 30 rps, 90 s  -> CPU cost per request
perf/run.sh baseline         # 60 s @ 50 rps warm-up, then 150 rps for 180 s (NFR-01)
perf/run.sh stress           # 50 -> 600 rps ramp, 5 min
perf/run-degradation.sh      # 150 rps with injected dependency failures
```

Every run stores: `environment.txt`, `k6-summary.txt`, `server-metrics.txt` (Prometheus, server-side incl.
persistence), `jvm/` (thread dump, virtual-thread dump, heap info, class histogram, JFR views). Raw k6 JSON
is in `results/raw/`. Open-model executors (constant/ramping arrival rate) are used so that a slow server
does not slow the load generator down (avoids coordinated omission).

**Target (NFR-01):** p95 ≤ 100 ms and p99 ≤ 250 ms at 150 TPS sustained on one instance, error rate < 0.1%.

## Results history (all runs kept, including failures)

| Run | Change under test | Sustained p95 | Sustained p99 | Errors | Degraded | Verdict |
|---|---|---|---|---|---|---|
| [baseline-01](results/baseline-01-initial/) | first run, JVM restarted before the run | 496 ms | 1,520 ms | 0.58% | 36.8% | **FAIL** |
| [baseline-02](results/baseline-02-profiled/) | same code, JFR captured | 208 ms | 1,607 ms | 0.59% | 28.5% | **FAIL** |
| [baseline-03](results/baseline-03-bounded-fallback/) | bounded PostgreSQL fallback, breaker slow-call 250 ms, cached meters (J-18) | 337 ms | 1,495 ms | 0.27% | 34.1% | **FAIL** (5xx 137 → 3) |
| [calibration-01](results/calibration-01-before/) | 30 rps on a warm JVM | 13.8 ms* | 18.4 ms* | 0% | 0% | **11.7 CPU-ms/request** |
| [calibration-02/03](results/calibration-03-inline-redis-warm/) | Redis lookups inline instead of on virtual threads (J-19) | 15.1 ms* | 19.1 ms* | 0% | 0% | 11.8 CPU-ms — **no gain, reverted** |
| [stress-01](results/stress-01-no-admission-control/) | 50→600 rps, no admission control | knee ≈ 300 rps; beyond it p95 **2.4 s**, 53% errors | | | | **congestion collapse** (J-20) |
| [stress-02](results/stress-02-admission-control/) | admission control (32 in flight, 20 ms queue) | client p95 273 ms / p99 365 ms at 600 rps offered; 43% fast 503s | | | | **graceful degradation** |
| [baseline-04](results/baseline-04-warm-admission/) | JVM warmed by the stress run | **23.5 ms** | **75.5 ms** | 0.03% | 11.0% | **PASS** |
| [baseline-05](results/baseline-05-cb-window/) | time-based breaker window; JVM warmed only at 30 rps | 351 ms | 481 ms | >0.1% | 36.2% | **FAIL** — C2 JIT at 24% CPU (J-22) |
| [baseline-06](results/baseline-06-cb-window-warm/) | same code, warmed by a full baseline run | **18.4 ms** | **68.5 ms** | 0% | **0.54%** | **PASS** |
| [degradation](results/degradation/) | 150 rps, vendor +300 ms at 60 s, Redis frozen 120–150 s | 221 ms (client, whole run) | 441 ms | 3.36% (all 503 OVERLOADED) | 52% | 0 × 5xx errors, **0 data loss**, see below |

\* client-side overall (calibration has no sustained phase).

### Final state (baseline-06, warm JVM, 2 vCPU)
* 150 TPS sustained: **p50 12 ms, p95 18 ms, p99 68 ms (client)**; server-side p95 17 ms, p99 63 ms.
* CPU peak 83% of 2 vCPU; 0 connection-pool waits; outbox backlog ≤ 12 rows; GC young pauses 5–7 ms,
  total GC pause 0.94 s in 247 s (0.4%).
* Capacity on 2 vCPU: knee ≈ 300 rps offered; above it admission control sheds load instead of collapsing.

### What the target does and does not mean
* **Met only on a warm JVM.** A freshly started JVM spent ~24–30% of its CPU in the C2 JIT compiler for
  several minutes and failed the SLO (runs 01, 05). Production consequence: rolling deployments need
  gradual traffic ramp-up (slow start), warm-up traffic before readiness, or JIT/AOT caching (CRaC,
  Leyden AOT cache in newer JDKs) — see JAVA_PERFORMANCE_GUIDE.md.
* The load pool replays ~7,300 customers at 150 rps, so each customer transacts every ~50 s: velocity
  features are inflated and ~20% of decisions are REVIEW. The *decision mix* is not representative; the
  *cost per request* is.

### Degradation test (NFR-04 / NFR-05)
| Check | Result |
|---|---|
| Successful responses vs persisted decisions | 34,768 = 34,768 (**no data loss**) |
| Outbox after the run | 0 unpublished |
| HTTP 5xx from the service | 0 |
| Requests shed by admission control | 893 (2.5%), all in the ~90 s after the vendor slowdown started |
| Redis frozen 30 s | handled by breaker + bounded fallback — degraded decisions, **no** rejections |
| Vendor +300 ms | the harder case: timed-out HTTP calls abandon connections, new connections and CPU spikes cause other budgets to time out until the vendor circuit opens (J-23). Mitigation documented, not yet implemented |

NFR-05 ("never a 5xx for valid input") is therefore **partially met**: no errors from failures, but the
service deliberately sheds load (503 OVERLOADED + Retry-After) when a slow dependency pushes it to capacity.

## Lessons (journal J-18 … J-24)
1. A fallback more expensive than the primary amplifies overload — bound it.
2. Circuit-breaker windows must be sized to traffic volume and slow-call thresholds must exceed CPU jitter.
3. Without admission control, virtual threads accept everything and the system collapses below capacity.
4. Measure before optimising; compare at equal JVM warmth; revert changes that don't measurably help.
5. Classic thread dumps don't show virtual threads — use `jcmd <pid> Thread.dump_to_file -format=json`.
