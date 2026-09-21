#!/usr/bin/env bash
# A/B for journal J-26: sustained 200 rps for 120 s with the Lettuce pool disabled vs enabled.
# Records TIME_WAIT sockets to Redis, BindExceptions in the log, degraded share and latency.
source "$(dirname "$0")/lib.sh"
EV="$LAB_ROOT/troubleshooting-lab/evidence/redis-churn"; EVW="$WIN_ROOT/troubleshooting-lab/evidence/redis-churn"; mkdir -p "$EV"
tw() { docker exec $DS sh -c 'cat /proc/net/tcp /proc/net/tcp6 | awk "\$4==\"06\" && \$3 ~ /:18EB\$/" | wc -l'; }
for pool in false true; do
  (cd "$LAB_ROOT" && REDIS_POOL_ENABLED=$pool DECISION_PROFILES=lab docker compose -f deploy/docker-compose.yml up -d decision-service >/dev/null 2>&1)
  until [ "$(docker inspect -f '{{.State.Health.Status}}' $DS)" = healthy ]; do sleep 3; done
  load 100 60s TENANT=aldermoor-bank; sleep 65                      # warm-up, then let TIME_WAIT drain
  SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  load 200 120s TENANT=aldermoor-bank & L=$!
  peak=0; for i in $(seq 1 11); do sleep 10; n=$(tw); [ "$n" -gt "$peak" ] && peak=$n; done; wait $L
  bind=$(docker logs --since "$SINCE" $DS 2>&1 | grep -c "Cannot assign requested address")
  { echo "== pool.enabled=$pool  (200 rps x 120 s, after 60 s warm-up)"
    echo "peak TIME_WAIT->redis=$peak  log lines with 'Cannot assign requested address'=$bind"
    grep -E "requests=|latency|degraded" "$EV/k6.txt"; } | tee -a "$EV/pool-ab.txt"
done
