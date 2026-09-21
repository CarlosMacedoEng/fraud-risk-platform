#!/usr/bin/env bash
# TS-08 Duplicate event processing: the last 5 minutes of RiskDecisionCreated events are published again
# (what a relay crash between "sent" and "marked" or an operator replay does). Consumers must not act twice.
source "$(dirname "$0")/lib.sh"; evidence ts-08
CASES_BEFORE=$(sql -tAc "SELECT count(*) FROM fraud_cases")
CALLS_BEFORE=$(curl -s localhost:8090/cases/v1/cases | python -c "import sys,json; print(json.load(sys.stdin)['cases'])")
FROM=$(date -u -d '-5 minutes' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || python -c "import datetime;print((datetime.datetime.utcnow()-datetime.timedelta(minutes=5)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
TO=$(date -u -d '+1 minute' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || python -c "import datetime;print((datetime.datetime.utcnow()+datetime.timedelta(minutes=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
note "INCIDENT: replay RiskDecisionCreated $FROM .. $TO"
curl -s -o "$EVW/replay.json" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/events/replay -H "$ADMIN" \
  -H 'Content-Type: application/json' -d "{\"eventType\":\"RiskDecisionCreated\",\"from\":\"$FROM\",\"to\":\"$TO\"}"
sleep 25
CASES_AFTER=$(sql -tAc "SELECT count(*) FROM fraud_cases")
CALLS_AFTER=$(curl -s localhost:8090/cases/v1/cases | python -c "import sys,json; print(json.load(sys.stdin)['cases'])")
prom 'sum(increase(risk_consumer_duplicates_total[2m]))' > "$EV/duplicates-detected.txt"
{
  echo "events requeued: $(cat "$EV/replay.json")"
  echo "fraud_cases rows before/after: $CASES_BEFORE / $CASES_AFTER"
  echo "cases in external system before/after: $CALLS_BEFORE / $CALLS_AFTER"
  echo "duplicates detected by case-creator (2m): $(cat "$EV/duplicates-detected.txt")"
  sql -tAc "SELECT consumer_group, count(*) FROM processed_events GROUP BY 1 ORDER BY 1"
} | tee -a "$EV/timeline.txt"
