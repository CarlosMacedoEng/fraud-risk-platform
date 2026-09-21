#!/usr/bin/env bash
# TS-01 Slow PostgreSQL query: the keyset index behind GET /v1/decisions is dropped (e.g. by a bad migration).
source "$(dirname "$0")/lib.sh"; evidence ts-01
list() { curl -s -o /dev/null -w "%{time_total}" "localhost:8080/v1/decisions?limit=50" -H "$ANALYST"; }
Q="EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM risk_decisions WHERE tenant_id = 'aldermoor-bank' ORDER BY created_at DESC, decision_id DESC LIMIT 50"
note "baseline API latency: $(list)s $(list)s $(list)s"
sql -c "$Q" > "$EV/explain-before.txt"
sql -c "SELECT pg_stat_statements_reset()" > /dev/null
note "INCIDENT: DROP INDEX ix_decisions_tenant_created"
sql -c "DROP INDEX ix_decisions_tenant_created" > /dev/null
note "API latency after drop: $(list)s $(list)s $(list)s"
sql -c "$Q" > "$EV/explain-during.txt"
sql -c "SELECT calls, round(mean_exec_time::numeric,1) mean_ms, left(regexp_replace(query,'\s+',' ','g'),90) q FROM pg_stat_statements WHERE query ILIKE '%FROM risk_decisions WHERE tenant_id%ORDER BY%' ORDER BY mean_exec_time DESC LIMIT 3" > "$EV/pg_stat_statements.txt"
sql -c "SELECT relname, seq_scan, seq_tup_read, idx_scan FROM pg_stat_user_tables WHERE relname = 'risk_decisions'" > "$EV/table-scans.txt"
note "FIX: CREATE INDEX CONCURRENTLY (no write lock)"
sql -c "CREATE INDEX CONCURRENTLY ix_decisions_tenant_created ON risk_decisions (tenant_id, created_at DESC, decision_id DESC)" > /dev/null
note "API latency after fix: $(list)s $(list)s $(list)s"
sql -c "$Q" > "$EV/explain-after.txt"
grep -hE "Execution Time|Seq Scan|Index Scan|Sort Method" "$EV"/explain-*.txt | tee -a "$EV/timeline.txt"
