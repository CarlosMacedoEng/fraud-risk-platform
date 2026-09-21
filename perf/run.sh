#!/usr/bin/env bash
# Run a k6 scenario against the local compose stack and capture evidence:
#   environment, k6 summary, server-side percentiles (Prometheus), JVM diagnostics under load.
# Usage: perf/run.sh baseline|stress|degradation
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: do not rewrite container paths such as /dumps
SCENARIO=${1:-baseline}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WIN_ROOT="$(cd "$ROOT" && (pwd -W 2>/dev/null || pwd))"
OUT="$ROOT/perf/results/$SCENARIO"
mkdir -p "$OUT/jvm" "$ROOT/perf/results/raw"
DS=fraud-platform-decision-service-1

{
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git_commit=$(git -C "$WIN_ROOT" rev-parse --short HEAD)"
  echo "docker_host_cpus=$(docker info --format '{{.NCPU}}') docker_host_mem_bytes=$(docker info --format '{{.MemTotal}}')"
  echo "decision_service_limits=$(docker inspect $DS --format 'cpus={{.HostConfig.NanoCpus}} mem={{.HostConfig.Memory}}')"
  echo "java=$(docker exec $DS java -version 2>&1 | head -1)"
  echo "db_pool_max=$(docker exec $DS printenv DB_POOL_MAX || echo 20)"
} > "$OUT/environment.txt"

START=$(date +%s)
MSYS_NO_PATHCONV=1 docker run --rm --network fraud-platform_default -v "$WIN_ROOT/perf:/scripts" grafana/k6:1.3.0 run \
  -q -e SCENARIO="$SCENARIO" -e BASE_URL=http://decision-service:8080 /scripts/scoring.js > "$OUT/k6-summary.txt" 2>&1 &
K6=$!

# JVM evidence in the middle of the sustained phase.
sleep "${JVM_CAPTURE_AT:-100}"
docker exec $DS jcmd 1 Thread.print > "$OUT/jvm/thread-dump-under-load.txt" || true
# Virtual threads (all request handling) are NOT in Thread.print; they need the JSON thread dump.
docker exec $DS jcmd 1 Thread.dump_to_file -format=json -overwrite /dumps/$SCENARIO-threads.json > /dev/null || true
sleep 1; cp "$ROOT/data/runtime/dumps/decision-service/$SCENARIO-threads.json" "$OUT/jvm/thread-dump-virtual.json" 2>/dev/null || true
docker exec $DS jcmd 1 GC.heap_info > "$OUT/jvm/heap-info-under-load.txt" || true
docker exec $DS jcmd 1 JFR.start name=perf duration=60s settings=profile filename=/dumps/$SCENARIO.jfr > /dev/null || true
if [ -n "${DURING_LOAD_HOOK:-}" ]; then bash -c "$DURING_LOAD_HOOK" || true; fi

wait $K6 || true
END=$(date +%s)
sleep 5
docker exec $DS jcmd 1 GC.class_histogram > "$OUT/jvm/class-histogram.txt" 2>/dev/null || true
for view in hot-methods allocation-by-class gc thread-cpu-load; do
  docker exec $DS jfr view --width 200 $view /dumps/$SCENARIO.jfr > "$OUT/jvm/jfr-$view.txt" 2>&1 || true
done

# Server-side percentiles over the run window (Prometheus histogram, includes persistence).
RANGE=$(( END - START ))s
q() { curl -s --get "http://localhost:9090/api/v1/query" --data-urlencode "query=$1" --data-urlencode "time=$END" \
        | python -c "import sys,json; r=json.load(sys.stdin)['data']['result']; print(r[0]['value'][1] if r else 'n/a')"; }
{
  echo "window_seconds=$RANGE"
  for p in 0.5 0.95 0.99; do
    echo "server_latency_p${p}_seconds=$(q "histogram_quantile($p, sum by (le) (increase(risk_decision_latency_seconds_bucket[$RANGE])))")"
  done
  echo "decisions=$(q "sum(increase(risk_decisions_total[$RANGE]))")"
  echo "degraded=$(q "sum(increase(risk_decisions_degraded_total[$RANGE]))")"
  echo "http_5xx=$(q "sum(increase(http_server_requests_seconds_count{uri=\"/v1/decisions\",status=~\"5..\"}[$RANGE]))")"
  echo "gc_pause_seconds_total=$(q "sum(increase(jvm_gc_pause_seconds_sum{application=\"decision-service\"}[$RANGE]))")"
  echo "hikari_pending_max=$(q "max_over_time(hikaricp_connections_pending[$RANGE])")"
  echo "outbox_backlog_max=$(q "max_over_time(risk_outbox_backlog[$RANGE])")"
  echo "cpu_process_max=$(q "max_over_time(process_cpu_usage{application=\"decision-service\"}[$RANGE])")"
} > "$OUT/server-metrics.txt"
cat "$OUT/k6-summary.txt"; echo; cat "$OUT/server-metrics.txt"
