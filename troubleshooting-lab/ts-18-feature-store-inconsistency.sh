#!/usr/bin/env bash
# TS-18 Data inconsistency: Redis loses its state (FLUSHALL, as after an eviction storm, a failover to an empty
# replica or an operator mistake). No errors, no breaker opens - decisions just change. Measure the drift in
# features and reasons, then rebuild the feature store and graph from PostgreSQL.
source "$(dirname "$0")/lib.sh"; evidence ts-18
window() {  # label, since -> features and reasons for Aldermoor decisions since $2
  sql -tAc "
    WITH d AS (SELECT * FROM risk_decisions WHERE tenant_id='aldermoor-bank' AND created_at > '$2')
    SELECT '$1' AS phase, count(*) AS n,
           round(100.0*avg((decision='REVIEW')::int),1) AS review_pct,
           round(avg((feature_vector->>'is_new_device')::numeric),3) AS new_device,
           round(avg((feature_vector->>'account_txn_count_24h')::numeric),1) AS cnt_24h,
           round(avg(((feature_vector->>'graph_account_risk')::numeric + (feature_vector->>'graph_device_risk')::numeric)/2),4) AS graph_risk,
           round(100.0*avg((reasons::text LIKE '%NEW_DEVICE%')::int),1) AS pct_new_device_reason,
           round(100.0*avg((reasons::text LIKE '%HIGH_TRANSACTION_VELOCITY%')::int),1) AS pct_velocity_reason,
           round(100.0*avg((cardinality(degraded_modes) > 0)::int),1) AS pct_degraded
    FROM d"
}
echo "phase|n|review%|new_device|txn_24h|graph_risk|%NEW_DEVICE|%VELOCITY|%degraded" > "$EV/phases.txt"

T0=$(now); load 40 40s TENANT=aldermoor-bank; window baseline "$T0" >> "$EV/phases.txt"
note "INCIDENT: Redis state lost (keys before: $(docker exec fraud-platform-redis-1 redis-cli dbsize))"
docker exec fraud-platform-redis-1 redis-cli flushall > /dev/null
T1=$(now); load 40 40s TENANT=aldermoor-bank; window after-flush "$T1" >> "$EV/phases.txt"
prom 'max by (name) (resilience4j_circuitbreaker_state{state="open"})' > "$EV/breakers-after-flush.txt"
note "breakers during the inconsistency: $(tr '\n' ' ' < "$EV/breakers-after-flush.txt")"
note "RECOVERY: rebuild feature store (30 days) + reload graph snapshot"
curl -s -o "$EVW/rebuild.json" -w "rebuild HTTP %{http_code} in %{time_total}s\n" -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/feature-store/rebuild?days=30" -H "$ADMIN" | tee -a "$EV/timeline.txt"
curl -s -o "$EVW/graph.json" -w "graph reload HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/graph/reload -H "$ADMIN" | tee -a "$EV/timeline.txt"
curl -s -X POST "localhost:8080/v1/admin/tenants/quillon-pay/feature-store/rebuild?days=30" -H 'X-Api-Key: dev-quillon-admin-key' > "$EV/rebuild-quillon.json"
curl -s -X POST localhost:8080/v1/admin/tenants/quillon-pay/graph/reload -H 'X-Api-Key: dev-quillon-admin-key' > /dev/null
cat "$EV/rebuild.json" "$EV/graph.json" >> "$EV/timeline.txt"; echo >> "$EV/timeline.txt"
T2=$(now); load 40 40s TENANT=aldermoor-bank; window after-rebuild "$T2" >> "$EV/phases.txt"
column -t -s '|' "$EV/phases.txt" | tee -a "$EV/timeline.txt"
