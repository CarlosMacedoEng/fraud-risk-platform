#!/usr/bin/env bash
# TS-07 Message-consumer lag: the case-management system answers in 3 s (client attempt timeout 1.5 s, deadline 5 s).
# REVIEW decisions pile up on fraud.decisions.v1 for the case-creator group; exhausted records go to the DLT.
# Prerequisite: case-creator lag ~0 (see evidence/ts-07-unplanned for the broker-side incident found on the first run).
source "$(dirname "$0")/lib.sh"; evidence ts-07
export MSYS_NO_PATHCONV=1
group() { docker run --rm --network fraud-platform_default apache/kafka:4.1.0 /opt/kafka/bin/kafka-consumer-groups.sh \
            --bootstrap-server kafka:9092 --describe --group case-creator "$@" 2>/dev/null; }
lag() { group | awk '$1=="case-creator" && $6 ~ /^[0-9]+$/ {s+=$6} END{print s+0}'; }
state() { group --state | awk '$1=="case-creator" {print $(NF-1), "members="$NF}'; }
dlt_count() { curl -s "localhost:8080/v1/admin/tenants/aldermoor-bank/events/dlt?topic=fraud.decisions.v1.case-creator.dlt&max=5000" -H "$ADMIN" \
            | python -c "import sys,json; print(len(json.load(sys.stdin)))"; }

note "before: lag=$(lag) state=$(state) dlt=$(dlt_count)"
note "INCIDENT: case management +3 s latency; 20 rps of Aldermoor traffic for 90 s"
curl -s -X POST localhost:8090/admin/faults/cases -H 'Content-Type: application/json' \
  -d '{"latencyMs":3000,"errorRate":0,"errorStatus":0,"malformed":false}' > /dev/null
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
load 20 90s TENANT=aldermoor-bank & L=$!
for i in 1 2 3; do sleep 30; note "t+$((i*30))s lag=$(lag) state=$(state)"; done
wait $L
prom 'sum by (kind) (increase(risk_cases_dispatch_failures_total[2m]))' > "$EV/dispatch-failures.txt"
prom 'sum(increase(risk_dependency_timeouts_total[2m])) by (dependency)' > "$EV/dependency-timeouts.txt"
sql -tAc "SELECT status, count(*) FROM fraud_cases WHERE created_at > '$T0' GROUP BY 1" > "$EV/cases-by-status.txt"
docker logs --since "$T0" $DS 2>&1 | grep -oE "DLT group=case-creator|max.poll.interval|CommitFailedException|Revoking previously assigned|rebalance" | sort | uniq -c > "$EV/listener-log-signals.txt"
note "during fault: dlt=$(dlt_count)"

note "FIX: case management recovered (fault cleared)"
curl -s -X DELETE localhost:8090/admin/faults > /dev/null
T1=$(date +%s)
while :; do l=$(lag); [ "$l" -le 5 ] && break; [ $(( $(date +%s) - T1 )) -gt 300 ] && { note "lag not drained after 300 s"; break; }; sleep 10; done
note "lag drained to $(lag) in $(( $(date +%s) - T1 )) s after recovery"
note "DLT redrive (records that exhausted retries during the fault)"
curl -s -o "$EVW/redrive.json" -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/events/dlt/redrive?topic=fraud.decisions.v1.case-creator.dlt&max=5000" -H "$ADMIN"
cat "$EV/redrive.json" | tee -a "$EV/timeline.txt"; echo
sleep 30
note "after redrive: lag=$(lag)"
sql -tAc "SELECT status, count(*) FROM fraud_cases WHERE created_at > '$T0' GROUP BY 1" > "$EV/cases-after.txt"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
for f in dispatch-failures dependency-timeouts cases-by-status listener-log-signals cases-after; do echo "== $f"; cat "$EV/$f.txt"; done | tee -a "$EV/timeline.txt"
