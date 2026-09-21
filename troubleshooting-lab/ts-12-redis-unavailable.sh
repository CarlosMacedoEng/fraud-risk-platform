#!/usr/bin/env bash
# TS-12 Redis unavailability: Redis stops for 40 s under load, then comes back EMPTY (no persistence).
# Also the recovery for TS-18: rebuild velocity/"seen" state and graph features from the system of record.
source "$(dirname "$0")/lib.sh"; evidence ts-12
note "keys in Redis before incident: $(docker exec fraud-platform-redis-1 redis-cli dbsize)"
load 50 90s TENANT=aldermoor-bank & L=$!
sleep 15
note "INCIDENT: docker stop redis"
docker stop fraud-platform-redis-1 > /dev/null
sleep 20
prom 'sum by (mode) (increase(risk_decisions_degraded_total[20s]))' > "$EV/degraded-during.txt"
prom 'max by (name) (resilience4j_circuitbreaker_state{state="open"})' > "$EV/breaker-open.txt"
prom 'sum(increase(risk_featurestore_fallback_reads_total[20s]))' > "$EV/fallback-reads.txt"
prom 'sum(increase(risk_featurestore_fallback_rejected_total[20s]))' > "$EV/fallback-rejected.txt"
prom 'max(hikaricp_connections_pending)' > "$EV/hikari-pending.txt"
sleep 20
note "Redis restarted (empty: appendonly=no)"
docker start fraud-platform-redis-1 > /dev/null
wait $L
sleep 5
KEYS=$(docker exec fraud-platform-redis-1 redis-cli dbsize)
note "keys in Redis after restart + ~30 s of new traffic: $KEYS (history before the outage is gone; only new activity was written)"
note "RECOVERY: rebuild feature store from PostgreSQL + reload graph snapshot"
curl -s -o "$EVW/rebuild.json" -w "rebuild HTTP %{http_code} in %{time_total}s
" -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/feature-store/rebuild?days=30" -H "$ADMIN"
curl -s -o "$EVW/graph.json" -w "graph reload HTTP %{http_code}
" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/graph/reload -H "$ADMIN"
curl -s -X POST "localhost:8080/v1/admin/tenants/quillon-pay/feature-store/rebuild?days=30" -H 'X-Api-Key: dev-quillon-admin-key' > "$EV/rebuild-quillon.json"
curl -s -X POST localhost:8080/v1/admin/tenants/quillon-pay/graph/reload -H 'X-Api-Key: dev-quillon-admin-key' > /dev/null
note "keys after rebuild: $(docker exec fraud-platform-redis-1 redis-cli dbsize)"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
for f in degraded-during breaker-open fallback-reads fallback-rejected hikari-pending rebuild graph; do
  echo "== $f"; cat "$EV/$f.txt" 2>/dev/null || cat "$EV/$f.json"; echo
done | tee -a "$EV/timeline.txt"
