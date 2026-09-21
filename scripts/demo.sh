#!/usr/bin/env bash
# 10-minute interview demo (docs/INTERVIEW_DEMO_SCRIPT.md). Requires the compose stack; step 6 needs the
# decision-service started with the lab profile:
#   DECISION_PROFILES=lab docker compose -f deploy/docker-compose.yml up -d decision-service
# Interactive by default (press Enter between steps); DEMO_PAUSE=0 runs straight through.
set -uo pipefail
export MSYS_NO_PATHCONV=1
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WIN_ROOT="$(cd "$ROOT" && (pwd -W 2>/dev/null || pwd))"
OUT="$ROOT/data/runtime/demo"; mkdir -p "$OUT"; OUTW="$WIN_ROOT/data/runtime/demo"
GW='X-Api-Key: dev-aldermoor-gateway-key'; ADMIN='X-Api-Key: dev-aldermoor-admin-key'
APPROVER='X-Api-Key: dev-aldermoor-approver-key'; ANALYST='X-Api-Key: dev-aldermoor-analyst-key'
API=http://localhost:8080; T=aldermoor-bank; RUN=$(date +%s)
step() { echo; echo "=================== $1"; }
pause() { [ "${DEMO_PAUSE:-1}" = 1 ] && read -r -p "(Enter) " _ || true; }
sql() { docker exec fraud-platform-postgres-1 psql -U risk -d riskplatform -P pager=off "$@"; }
py() { python -c "$1"; }
now() { date -u +%Y-%m-%dT%H:%M:%S.000Z; }
score() {  # $1 = file name, $2 = transaction id, $3 = device, $4 = amount (default 2450)
  curl -s -o "$OUTW/$1.json" "$API/v1/decisions" -H "$GW" -H "Idempotency-Key: $2" -H 'Content-Type: application/json' \
    -d "{\"transactionId\":\"$2\",\"customerId\":\"ALD-C000100\",\"accountId\":\"ALD-A000100\",\"eventTime\":\"$(now)\",
         \"transactionType\":\"TRANSFER\",\"channel\":\"MOBILE\",\"amount\":${4:-2450.00},\"currency\":\"EUR\",
         \"beneficiaryId\":\"B-DEMO-$RUN\",\"beneficiaryCountry\":\"LT\",\"deviceId\":\"$3\",
         \"ipAddress\":\"91.198.10.20\",\"ipCountry\":\"LT\"}"
  py "
import json; d=json.load(open(r'$OUTW/$1.json'))
print('decision :', d['decision'], '| risk score', d.get('riskScore'), '| level', d.get('riskLevel'))
print('reasons  :', [r.get('code') for r in d.get('reasons', [])])
print('versions :', d.get('versions'))
print('latency  :', d.get('processingTimeMs'), 'ms (server) | degraded:', d.get('degradedModes'))
print('decisionId', d['decisionId'])"
}

step "1. Real-time decision: A2A transfer, new device, new beneficiary abroad"
TX1="DEMO-$RUN-1"; DEV="D-DEMO-$RUN"
score d1 "$TX1" "$DEV"
D1=$(py "import json; print(json.load(open(r'$OUTW/d1.json'))['decisionId'])")
pause

step "2. Model explanation for that decision (SHAP from the model service, off the hot path)"
curl -s -o "$OUTW/explain.json" "$API/v1/decisions/$D1/explanation" -H "$ANALYST"
py "
import json; e=json.load(open(r'$OUTW/explain.json'))
print('SHAP available:', e.get('shapAvailable'), '| model', e.get('modelVersion'))
shap=e.get('shap') or {}
items=shap.get('topContributions') or shap.get('contributions') or []
for c in items[:5]: print('  ', c)
if not items: print('  ', json.dumps(shap)[:400])
print('stored reasons:', [r.get('code') for r in e.get('decisionReasons', [])])"
pause

step "3. Event-driven integration: outbox events for the decision, relay to Kafka, case for REVIEW"
sleep 2
sql -c "SELECT event_type, topic, published_at IS NOT NULL AS published FROM outbox_events WHERE payload::text LIKE '%$TX1%' ORDER BY created_at"
echo "--- a moderate transfer from a new device (typically REVIEW) -> RiskDecisionCreated -> case-creator -> case API"
TX3="DEMO-$RUN-3"; score d3 "$TX3" "D-DEMO-$RUN-B" 180.00
sleep 3
sql -c "SELECT d.decision, c.status AS case_status, c.priority, c.external_case_ref FROM risk_decisions d LEFT JOIN fraud_cases c USING (decision_id) WHERE d.transaction_id = '$TX3'"
pause

step "4. Customer configuration change: emergency block of the device, approved by a second person, then rollback"
V="1.9.$((RUN % 100000))"
curl -s -o "$OUTW/derive.json" -w "derive draft %{http_code}\n" -X POST "$API/v1/admin/tenants/$T/strategies/1.1.0/derive" -H "$ADMIN" \
  -H 'Content-Type: application/json' -d "{\"newVersion\":\"$V\",\"changeSummary\":\"demo: block device $DEV\",\"emergency\":true,\"listAdditions\":{\"blockedDevices\":[\"$DEV\"]}}"
curl -s -o /dev/null -w "self-approval by the author: %{http_code} (four-eyes)\n" -X POST "$API/v1/admin/tenants/$T/strategies/$V/approve" -H "$ADMIN" -H 'Content-Type: application/json' -d '{}'
curl -s -o /dev/null -w "approval by the approver: %{http_code}\n" -X POST "$API/v1/admin/tenants/$T/strategies/$V/approve" -H "$APPROVER" -H 'Content-Type: application/json' -d '{"comment":"demo"}'
curl -s -o /dev/null -w "promote to dev (100%%): %{http_code}\n" -X POST "$API/v1/admin/tenants/$T/deployments/dev/promote" -H "$ADMIN" \
  -H 'Content-Type: application/json' -d "{\"version\":\"$V\",\"rolloutPercentage\":100,\"reason\":\"demo emergency block\"}"
echo "--- same customer and device again:"
score d2 "DEMO-$RUN-2" "$DEV"
curl -s -o /dev/null -w "rollback: %{http_code}\n" -X POST "$API/v1/admin/tenants/$T/deployments/dev/rollback" -H "$ADMIN" \
  -H 'Content-Type: application/json' -d '{"reason":"demo finished"}'
sql -c "SELECT created_at::time(0), actor, action, entity_id FROM audit_events WHERE tenant_id='$T' ORDER BY audit_id DESC LIMIT 5"
pause

step "5. File ingestion from a legacy system: a valid file and a malformed one"
python - "$WIN_ROOT" "$RUN" <<'PYEOF'
import sys, hashlib, pathlib
root, run = pathlib.Path(sys.argv[1]), sys.argv[2]
src = root / "data/samples/files/troubleshooting/aldermoor-bank/TXN_HISTORY_aldermoor-bank_20260430_903.csv"
lines = src.read_text(encoding="utf-8").splitlines()
out = [lines[0]] + [l.replace("ALD-T", f"ALD-T{run}-", 1) for l in lines[1:]]
inbound = root / "data/runtime/files/inbound"; inbound.mkdir(parents=True, exist_ok=True)
name = f"TXN_HISTORY_aldermoor-bank_20260430_{int(run) % 900 + 100}.csv"
data = ("\n".join(out) + "\n").encode()
(inbound / name).write_bytes(data)
(inbound / (name + ".done")).write_text(f"records={len(out)-1}\nsha256={hashlib.sha256(data).hexdigest()}\n")
print("delivered", name, len(out) - 1, "records")
PYEOF
M="$ROOT/data/samples/files/troubleshooting/aldermoor-bank/TXN_HISTORY_aldermoor-bank_20260430_902.csv"
cp "$M" "$M.done" "$ROOT/data/runtime/files/inbound/"
curl -s -o "$OUTW/scan.json" -X POST http://localhost:8081/v1/ingestion/scan -H 'X-Api-Key: dev-file-ops-key'
py "
import json
for r in json.load(open(r'$OUTW/scan.json', encoding='utf-8')):
    rep = r.get('report') or {}; rep = json.loads(rep) if isinstance(rep, str) else rep
    print(f\"{rep.get('file','?'):48s} {r.get('status'):26s} records={rep.get('records')} events={rep.get('eventsPublished')} reason={r.get('reason')}\")"
pause

step "6. Troubleshooting: the device-intelligence vendor becomes slow (+250 ms)"
curl -s -X POST localhost:8090/admin/faults/device-intel -H 'Content-Type: application/json' \
  -d '{"latencyMs":250,"errorRate":0,"errorStatus":0,"malformed":false}' > /dev/null
docker run --rm --network fraud-platform_default -v "$WIN_ROOT/perf:/scripts" grafana/k6:1.3.0 run -q \
  -e SCENARIO=lab -e RATE=30 -e DURATION=25s -e TENANT=aldermoor-bank -e BASE_URL=http://decision-service:8080 \
  /scripts/scoring.js 2>/dev/null | grep -E "requests=|client latency|degraded"
curl -s -X DELETE localhost:8090/admin/faults > /dev/null
q() { curl -s --get localhost:9090/api/v1/query --data-urlencode "query=$1" -o "$OUTW/q.json"; py "
import json; r=json.load(open(r'$OUTW/q.json'))['data']['result']; print('$2', [(x['metric'].get('$3',''), round(float(x['value'][1]))) for x in r if float(x['value'][1])>0])"; }
sleep 6
q 'sum by (mode) (increase(risk_decisions_degraded_total[1m]))' 'degraded by mode (1m):' mode
q 'max by (integration) (integration_circuit_state)' 'circuit state (2 = OPEN):' integration
echo "-> latency stayed within budget; the decision ran without the device signal (see playbook TS-06)"
pause

step "7. Performance: live percentiles and the recorded test results"
q 'histogram_quantile(0.95, sum by (le) (rate(risk_decision_latency_seconds_bucket[5m]))) * 1000' 'server p95 ms (5m):' x
q 'histogram_quantile(0.99, sum by (le) (rate(risk_decision_latency_seconds_bucket[5m]))) * 1000' 'server p99 ms (5m):' x
echo "Recorded: warm 150 TPS p95 15.2 ms / p99 40.1 ms; cold JVM p95 327 ms (perf/README.md, TS-15)."
echo "Grafana: http://localhost:3000 (dashboard 'Fraud platform')"
