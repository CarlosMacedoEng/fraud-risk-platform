#!/usr/bin/env bash
# Release 1.x -> 2.0 migration rehearsal on the local stack (real data volume of the lab database).
# Follows docs/MIGRATION_AND_UPGRADE_RUNBOOK.md step by step and records evidence in migration-rehearsal/evidence/.
# Prerequisites: images fraud-platform-decision-service:rel1 (previous release) and :latest (2.0) built.
source "$(dirname "$0")/../troubleshooting-lab/lib.sh"
export MSYS_NO_PATHCONV=1
EV="$LAB_ROOT/migration-rehearsal/evidence"; EVW="$WIN_ROOT/migration-rehearsal/evidence"
rm -rf "$EV"; mkdir -p "$EV"; : > "$EV/timeline.txt"
status() { for t in aldermoor-bank quillon-pay; do
  key=$([ $t = quillon-pay ] && echo dev-quillon-admin-key || echo dev-aldermoor-admin-key)
  curl -s "localhost:8080/v1/admin/tenants/$t/migrations/decision-channel" -H "X-Api-Key: $key"; echo; done; }
summary() { grep -E "requests=|client latency|server decision|degraded" "$EV/k6.txt"; }

note "== T-0 PRE-CHECKS (release 1.x running)"
sql -tAc "SELECT version, description, installed_on::timestamp(0) FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1" | tee -a "$EV/timeline.txt"
sql -tAc "SELECT tenant_id, count(*) FROM risk_decisions GROUP BY 1" | tee -a "$EV/timeline.txt"
sql -tAc "SELECT pg_size_pretty(pg_total_relation_size('risk_decisions')) AS risk_decisions_size" | tee -a "$EV/timeline.txt"
note "backup (pg_dump -Fc) before the change"
s=$(date +%s); docker exec fraud-platform-postgres-1 pg_dump -U risk -Fc -f /tmp/pre-2.0.dump riskplatform
note "backup took $(( $(date +%s) - s )) s, size $(docker exec fraud-platform-postgres-1 du -h /tmp/pre-2.0.dump | cut -f1)"

note "== STEP 1 DEPLOY 2.0 (Flyway applies V6 expand at startup)"
T0=$(date +%s)
(cd "$LAB_ROOT" && DECISION_PROFILES=lab docker compose -f deploy/docker-compose.yml up -d --force-recreate decision-service >/dev/null 2>&1)
until [ "$(docker inspect -f '{{.State.Health.Status}}' $DS)" = healthy ]; do sleep 2; done
note "2.0 ready after $(( $(date +%s) - T0 )) s"
docker logs $DS 2>&1 | grep -oE '"message":"(Migrating schema[^"]*|Successfully applied[^"]*)' | sed 's/"message":"//' | tee -a "$EV/timeline.txt"
sql -tAc "SELECT version, description, execution_time AS ms, success FROM flyway_schema_history WHERE version='6'" | tee -a "$EV/timeline.txt"

note "== STEP 2 SMOKE: new decisions carry the channel"
TX="MIG20-$(date +%s)"
score smoke "{\"transactionId\":\"$TX\",\"customerId\":\"ALD-C000100\",\"accountId\":\"ALD-A000100\",\"eventTime\":\"$(now)\",\"transactionType\":\"CARD_PAYMENT\",\"channel\":\"POS\",\"amount\":12.00,\"currency\":\"EUR\",\"cardToken\":\"tok_MIG\",\"merchantId\":\"M000010\",\"mcc\":\"5411\",\"merchantCountry\":\"PT\"}" | tee -a "$EV/timeline.txt"
note "channel of the smoke decision: $(sql -tAc "SELECT channel FROM risk_decisions WHERE transaction_id='$TX'")"

note "== STEP 3 RECONCILIATION BEFORE BACKFILL"
status | tee "$EV/status-before.json" | tee -a "$EV/timeline.txt"

note "== STEP 4 LATENCY REFERENCE: 50 rps, 60 s, no backfill"
load 50 60s; summary | tee -a "$EV/timeline.txt"; cp "$EV/k6.txt" "$EV/k6-reference.txt"

note "== STEP 5 BACKFILL UNDER LOAD: 50 rps while both tenants are backfilled (batch 5000, pause 50 ms)"
load 50 90s & L=$!
sleep 10
curl -s -o "$EVW/backfill-aldermoor.json" -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/migrations/decision-channel/backfill?batchSize=5000&pauseMs=50" -H "$ADMIN"
curl -s -o "$EVW/backfill-quillon.json" -X POST "localhost:8080/v1/admin/tenants/quillon-pay/migrations/decision-channel/backfill?batchSize=5000&pauseMs=50" -H 'X-Api-Key: dev-quillon-admin-key'
wait $L; cp "$EV/k6.txt" "$EV/k6-during-backfill.txt"; summary | tee -a "$EV/timeline.txt"
python -c "
import json
for t in ('aldermoor','quillon'):
    r=json.load(open(r'$EVW/backfill-'+t+'.json'))
    print(t, {k: r[k] for k in ('batches','updated','durationMs','complete')})" | tee -a "$EV/timeline.txt"

note "== STEP 6 RECONCILIATION AFTER BACKFILL (gate for V7)"
status | tee "$EV/status-after.json" | tee -a "$EV/timeline.txt"

note "== STEP 7 ROLLBACK REHEARSAL: release 1.x against the V6 schema"
docker rm -f ds-rel1 >/dev/null 2>&1
docker run -d --name ds-rel1 --network fraud-platform_default --memory 1536m \
  -e DB_URL=jdbc:postgresql://postgres:5432/riskplatform -e DB_USER=risk -e DB_PASSWORD=risk -e PLATFORM_ENV=dev \
  -e REDIS_HOST=redis -e MODELS_DIR=/app/models -e CONFIG_DIR=/app/config -e MESSAGING_ENABLED=false \
  -v "$WIN_ROOT/models:/app/models:ro" -v "$WIN_ROOT/config:/app/config:ro" fraud-platform-decision-service:rel1 >/dev/null
for i in $(seq 1 30); do
  r=$(docker run --rm --network fraud-platform_default curlimages/curl:8.10.1 -s -m 3 -o /dev/null -w "%{http_code}" http://ds-rel1:8080/actuator/health/readiness 2>/dev/null)
  [ "$r" = 200 ] && break; st=$(docker inspect -f '{{.State.Status}}' ds-rel1); [ "$st" = exited ] && break; sleep 5
done
note "release 1.x on V6 schema: state=$(docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' ds-rel1) readiness=$r"
docker logs ds-rel1 2>&1 | grep -oE '"message":"[^"]*(Flyway|schema|migration|Migration)[^"]*' | sed 's/"message":"//' | head -5 | tee -a "$EV/timeline.txt"
TX1="REL1-$(date +%s)"
BODY="{\"transactionId\":\"$TX1\",\"customerId\":\"ALD-C000101\",\"accountId\":\"ALD-A000101\",\"eventTime\":\"$(now)\",\"transactionType\":\"CARD_PAYMENT\",\"channel\":\"ECOM\",\"amount\":15.00,\"currency\":\"EUR\",\"cardToken\":\"tok_REL1\",\"merchantId\":\"M000010\",\"mcc\":\"5411\",\"merchantCountry\":\"PT\"}"
docker run --rm --network fraud-platform_default curlimages/curl:8.10.1 -s -o /dev/null -w "release 1.x scoring: HTTP %{http_code}\n" \
  http://ds-rel1:8080/v1/decisions -H 'X-Api-Key: dev-aldermoor-gateway-key' -H "Idempotency-Key: $TX1" -H 'Content-Type: application/json' -d "$BODY" | tee -a "$EV/timeline.txt"
note "channel written by release 1.x: '$(sql -tAc "SELECT coalesce(channel,'NULL') FROM risk_decisions WHERE transaction_id='$TX1'")' -> rows written during a rollback need the backfill again before V7"
docker logs ds-rel1 > "$EV/rel1-container-log.txt" 2>&1; docker rm -f ds-rel1 >/dev/null
note "re-run backfill after the rollback window"
curl -s -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/migrations/decision-channel/backfill?batchSize=5000&pauseMs=0" -H "$ADMIN" \
  | python -c "import sys,json; r=json.load(sys.stdin); print('updated', r['updated'], '| remaining', r['status']['remaining'], '| readyForContract', r['status']['readyForContract'])" | tee -a "$EV/timeline.txt"
docker exec fraud-platform-postgres-1 rm -f /tmp/pre-2.0.dump
