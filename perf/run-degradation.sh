#!/usr/bin/env bash
# Degradation test: 150 rps for 4 minutes while real dependencies fail.
#   t=60s   device-intelligence vendor +300 ms latency (simulator fault injection)
#   t=120s  Redis frozen (docker pause) for 30 s
#   t=180s  all faults removed
# Verifies NFR-04/05: no 5xx for valid input, documented degraded decisions, no data loss.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/perf/results/degradation"
PG="docker exec fraud-platform-postgres-1 psql -U risk -d riskplatform -tAc"
BEFORE=$($PG "select count(*) from risk_decisions where transaction_id like 'K6-%'")

JVM_CAPTURE_AT=200 "$ROOT/perf/run.sh" degradation > /dev/null 2>&1 &
RUN=$!
log() { echo "$(date -u +%H:%M:%S) $*" | tee -a "$OUT.timeline.txt"; }
: > "$OUT.timeline.txt"
log "load started (150 rps)"
sleep 60
curl -fsS -X POST localhost:8090/admin/faults/device-intel -H 'Content-Type: application/json' \
  -d '{"latencyMs":300,"errorRate":0,"errorStatus":0,"malformed":false}' > /dev/null && log "FAULT device-intel +300 ms"
sleep 60
docker pause fraud-platform-redis-1 > /dev/null && log "FAULT redis paused"
sleep 30
docker unpause fraud-platform-redis-1 > /dev/null && log "redis resumed"
sleep 30
curl -fsS -X DELETE localhost:8090/admin/faults > /dev/null && log "faults cleared"
wait $RUN || true
sleep 10
AFTER=$($PG "select count(*) from risk_decisions where transaction_id like 'K6-%'")
UNPUBLISHED=$($PG "select count(*) from outbox_events where published_at is null")
mv "$OUT.timeline.txt" "$OUT/timeline.txt"
{
  echo "decisions_persisted_during_run=$((AFTER - BEFORE))"
  echo "k6_successful_decisions=$(python -c "import json,glob,os; f=max(glob.glob(r'$(cd $ROOT && pwd -W)/perf/results/raw/degradation-*.json'),key=os.path.getmtime); print(int(json.load(open(f))['metrics']['decisions']['values']['count']))")"
  echo "outbox_unpublished_after_run=$UNPUBLISHED"
} > "$OUT/data-integrity.txt"
cat "$OUT/k6-summary.txt" "$OUT/server-metrics.txt" "$OUT/timeline.txt" "$OUT/data-integrity.txt"
