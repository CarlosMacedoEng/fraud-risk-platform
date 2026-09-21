#!/usr/bin/env bash
# Measures Redis TCP connection churn: TIME_WAIT sockets towards :6379 (hex 18EB) after 40 s at 100 rps.
# Usage: redis-churn-check.sh <label>   -> evidence/redis-churn/redis-churn-<label>.txt
source "$(dirname "$0")/lib.sh"
EV="$LAB_ROOT/troubleshooting-lab/evidence/redis-churn"; EVW="$WIN_ROOT/troubleshooting-lab/evidence/redis-churn"; mkdir -p "$EV"
tw() { docker exec $DS sh -c 'cat /proc/net/tcp /proc/net/tcp6 | awk "\$4==\"06\" && \$3 ~ /:18EB\$/" | wc -l'; }
est() { docker exec $DS sh -c 'cat /proc/net/tcp /proc/net/tcp6 | awk "\$4==\"01\" && \$3 ~ /:18EB\$/" | wc -l'; }
OUT="$EV/redis-churn-$1.txt"
{
  echo "label=$1 at $(date -u +%T)  ephemeral range: $(docker exec $DS cat /proc/sys/net/ipv4/ip_local_port_range)"
  echo "before load: TIME_WAIT->redis=$(tw) ESTABLISHED->redis=$(est)"
  load 100 40s TENANT=aldermoor-bank
  echo "after 40s @100rps: TIME_WAIT->redis=$(tw) ESTABLISHED->redis=$(est)"
  grep -E "requests=|latency|degraded" "$EV/k6.txt"
} | tee "$OUT"
