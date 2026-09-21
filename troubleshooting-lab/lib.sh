#!/usr/bin/env bash
# Shared helpers for the troubleshooting lab. Requires the compose stack; the decision-service must run with
# the lab profile for fault-injection incidents:  DECISION_PROFILES=lab docker compose -f deploy/docker-compose.yml up -d decision-service
set -uo pipefail
export MSYS_NO_PATHCONV=1
LAB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WIN_ROOT="$(cd "$LAB_ROOT" && (pwd -W 2>/dev/null || pwd))"
DS=fraud-platform-decision-service-1
ADMIN='X-Api-Key: dev-aldermoor-admin-key'
ANALYST='X-Api-Key: dev-aldermoor-analyst-key'

evidence() {
  EV="$LAB_ROOT/troubleshooting-lab/evidence/$1"; EVW="$WIN_ROOT/troubleshooting-lab/evidence/$1"   # EVW: for native tools
  rm -rf "$EV"; mkdir -p "$EV"; : > "$EV/timeline.txt"
}
note() { echo "$(date -u +%H:%M:%S) $*" | tee -a "$EV/timeline.txt"; }

# psql one-liner (unaligned, tuples only unless -H passed)
sql() { docker exec fraud-platform-postgres-1 psql -U risk -d riskplatform -P pager=off "$@"; }

# Instant Prometheus query -> "labels value" lines
prom() {
  curl -s --get http://localhost:9090/api/v1/query --data-urlencode "query=$1" -o "$EVW/.prom.json"
  python -c "
import json
r=json.load(open(r'$WIN_ROOT/troubleshooting-lab/evidence/$(basename "$EV")/.prom.json'))['data']['result']
print('\n'.join(f\"{dict(x['metric'])} {round(float(x['value'][1]),4)}\" for x in r) or '(no data)')"
}

# Background load: rate, duration, extra k6 env (e.g. TENANT=aldermoor-bank)
load() {
  local rate=$1 dur=$2; shift 2
  local extra=""; for kv in "$@"; do extra="$extra -e $kv"; done
  docker run --rm --network fraud-platform_default -v "$WIN_ROOT/perf:/scripts" grafana/k6:1.3.0 run -q \
    -e SCENARIO=lab -e RATE="$rate" -e DURATION="$dur" $extra -e BASE_URL=http://decision-service:8080 \
    /scripts/scoring.js > "$EV/k6.txt" 2>&1
}

lab_fault() { curl -s -X POST "localhost:8080/lab/faults/$1" -H "$ADMIN" -H 'Content-Type: application/json' -d "$2" > /dev/null; }
lab_clear() { curl -s -X DELETE localhost:8080/lab/faults -H "$ADMIN" > /dev/null; curl -s -X DELETE localhost:8090/admin/faults > /dev/null; }

# Score one transaction; writes the JSON response to $EV/$1.json
score() {
  local name=$1 body=$2 tx
  tx=$(python -c "import json,sys; print(json.loads(sys.argv[1])['transactionId'])" "$body")
  curl -s -o "$EVW/$name.json" -w "%{http_code} %{time_total}s\n" localhost:8080/v1/decisions \
    -H 'X-Api-Key: dev-aldermoor-gateway-key' -H "Idempotency-Key: $tx" -H 'Content-Type: application/json' -d "$body"
}

now() { date -u +%Y-%m-%dT%H:%M:%S.000Z; }
