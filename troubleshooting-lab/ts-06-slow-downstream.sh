#!/usr/bin/env bash
# TS-06 Slow downstream REST dependency: the device-intelligence vendor responds in 250 ms (budget 55 ms).
source "$(dirname "$0")/lib.sh"; evidence ts-06
load 60 60s & L=$!
sleep 15
note "INCIDENT: device-intel +250 ms latency"
curl -s -X POST localhost:8090/admin/faults/device-intel -H 'Content-Type: application/json' \
  -d '{"latencyMs":250,"errorRate":0,"errorStatus":0,"malformed":false}' > /dev/null
sleep 25
prom 'sum by (outcome) (increase(integration_client_requests_seconds_count{integration="device-risk"}[30s]))' > "$EV/device-outcomes.txt"
prom 'max(integration_circuit_state{integration="device-risk"})' > "$EV/circuit-state.txt"
prom 'sum by (mode) (increase(risk_decisions_degraded_total[30s]))' > "$EV/degraded.txt"
wait $L
curl -s -X DELETE localhost:8090/admin/faults > /dev/null
note "FIX: vendor recovered (fault cleared); circuit half-opens and closes"
sleep 15
prom 'max(integration_circuit_state{integration="device-risk"})' > "$EV/circuit-state-after.txt"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
for f in device-outcomes circuit-state degraded circuit-state-after; do echo "== $f"; cat "$EV/$f.txt"; done | tee -a "$EV/timeline.txt"
