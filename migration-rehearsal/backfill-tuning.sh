#!/usr/bin/env bash
# Backfill throttle tuning: resets risk_decisions.channel to NULL (lab only), then runs the Aldermoor backfill under
# 50 rps of scoring with the given batch size and pause. Usage: backfill-tuning.sh <batchSize> <pauseMs> [tag] [maxBatches]
source "$(dirname "$0")/../troubleshooting-lab/lib.sh"
B=${1:-1000}; P=${2:-100}; M=${4:-100000}
TAG=${3:-}; EV="$LAB_ROOT/migration-rehearsal/evidence/tuning-b$B-p$P$TAG"; EVW="$WIN_ROOT/migration-rehearsal/evidence/tuning-b$B-p$P$TAG"
rm -rf "$EV"; mkdir -p "$EV"; : > "$EV/timeline.txt"
size() { sql -tAc "SELECT pg_size_pretty(pg_total_relation_size('risk_decisions')), n_dead_tup FROM pg_stat_user_tables WHERE relname='risk_decisions'"; }
note "reset: channel -> NULL for aldermoor-bank, then VACUUM (ANALYZE)"
sql -tAc "SET statement_timeout=0; UPDATE risk_decisions SET channel = NULL WHERE tenant_id='aldermoor-bank'" | tee -a "$EV/timeline.txt"
sql -c "VACUUM (ANALYZE) risk_decisions" >/dev/null
sql -c "CHECKPOINT" >/dev/null    # flush the reset's WAL first: earlier runs measured that checkpoint, not the backfill
note "table size / dead tuples before backfill: $(size)"
load 50 150s & L=$!
sleep 10
curl -s -o "$EVW/backfill.json" -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/migrations/decision-channel/backfill?batchSize=$B&pauseMs=$P&maxBatches=$M" -H "$ADMIN"
wait $L
python -c "
import json; r=json.load(open(r'$EVW/backfill.json')); print({k: r.get(k) for k in ('batches','updated','durationMs','complete','status')})" | tee -a "$EV/timeline.txt"
grep -E "requests=|client latency|server decision|degraded" "$EV/k6.txt" | tee -a "$EV/timeline.txt"
note "table size / dead tuples after backfill: $(size)"
s=$(date +%s); sql -c "VACUUM (ANALYZE) risk_decisions" >/dev/null
note "VACUUM (ANALYZE) took $(( $(date +%s) - s )) s; after: $(size)"
