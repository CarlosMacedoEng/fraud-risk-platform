#!/usr/bin/env bash
# TS-16 Failed deployment. A new decision-service instance is started next to the running one (as a rolling update
# would) with (A) a wrong database secret and (B) a wrong models path. The running instance keeps serving.
source "$(dirname "$0")/lib.sh"; evidence ts-16
export MSYS_NO_PATHCONV=1
IMG=fraud-platform-decision-service
run_new() {  # name, extra env...
  local name=$1; shift
  docker rm -f "$name" >/dev/null 2>&1
  docker run -d --name "$name" --network fraud-platform_default --memory 1536m \
    -e DB_URL=jdbc:postgresql://postgres:5432/riskplatform -e DB_USER=risk -e KAFKA_BOOTSTRAP=kafka:9092 \
    -e PLATFORM_ENV=dev -e REDIS_HOST=redis -e CONFIG_DIR=/app/config -e MESSAGING_ENABLED=false \
    -v "$WIN_ROOT/models:/app/models:ro" -v "$WIN_ROOT/config:/app/config:ro" "$@" $IMG >/dev/null
}
probe() {  # name -> readiness/liveness from inside the network
  docker run --rm --network fraud-platform_default curlimages/curl:8.10.1 -s -m 3 -o /dev/null -w "%{http_code}" "http://$1:8080/actuator/health/$2" 2>/dev/null || echo "---"
}
watch_new() {  # name, seconds
  local name=$1 secs=$2 t0; t0=$(date +%s)
  while [ $(( $(date +%s) - t0 )) -lt "$secs" ]; do
    st=$(docker inspect -f '{{.State.Status}} exit={{.State.ExitCode}}' "$name" 2>/dev/null)
    note "$name: $st readiness=$(probe "$name" readiness) liveness=$(probe "$name" liveness)"
    case "$st" in exited*) break;; esac
    sleep 10
  done
}
serving() { score s "{\"transactionId\":\"TS16-$RANDOM$RANDOM\",\"customerId\":\"ALD-C000100\",\"accountId\":\"ALD-A000100\",\"eventTime\":\"$(now)\",\"transactionType\":\"CARD_PAYMENT\",\"channel\":\"POS\",\"amount\":25.00,\"currency\":\"EUR\",\"cardToken\":\"tok_TS16\",\"merchantId\":\"M000010\",\"mcc\":\"5411\",\"merchantCountry\":\"PT\"}"; }

note "A) new version deployed with a rotated-but-not-updated DB secret"
run_new ds-v2-badsecret -e DB_PASSWORD=rotated-secret-not-in-vault -e MODELS_DIR=/app/models
watch_new ds-v2-badsecret 90
docker logs ds-v2-badsecret 2>&1 | grep -oE "FATAL: password authentication failed for user \"[a-z]+\"|APPLICATION FAILED TO START|Failed to initialize pool[^\"]{0,80}|Unable to obtain connection[^\"]{0,60}" | sort | uniq -c | tee -a "$EV/timeline.txt"
note "running instance during the failed rollout: HTTP $(serving)"

note "B) new version deployed with a wrong models path (config drift)"
run_new ds-v2-nomodels -e DB_PASSWORD=risk -e MODELS_DIR=/app/models-v2
watch_new ds-v2-nomodels 100
docker logs ds-v2-nomodels 2>&1 | grep -iE "model|APPLICATION FAILED" | grep -iE "error|fail|not found|missing|no such" | head -5 | cut -c1-300 | tee -a "$EV/timeline.txt"
docker run --rm --network fraud-platform_default curlimages/curl:8.10.1 -s -m 3 http://ds-v2-nomodels:8080/actuator/health/readiness > "$EV/nomodels-readiness.json" 2>/dev/null
note "readiness body: $(cut -c1-400 "$EV/nomodels-readiness.json")"
note "running instance during the failed rollout: HTTP $(serving)"

note "FIX: correct secret + path -> the new instance becomes ready, then is removed (lab only)"
run_new ds-v2-fixed -e DB_PASSWORD=risk -e MODELS_DIR=/app/models
watch_new ds-v2-fixed 120
for n in ds-v2-badsecret ds-v2-nomodels ds-v2-fixed; do docker logs "$n" > "$EV/$n-log.txt" 2>&1; docker rm -f "$n" >/dev/null 2>&1; done
