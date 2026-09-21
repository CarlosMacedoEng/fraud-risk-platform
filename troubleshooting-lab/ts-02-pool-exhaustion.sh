#!/usr/bin/env bash
# TS-02 Connection-pool exhaustion: a slow statement inside the decision transaction holds connections.
source "$(dirname "$0")/lib.sh"; evidence ts-02
note "INCIDENT: each decision transaction now takes +400 ms (lab fault DECISION_PERSISTENCE), 60 rps"
lab_fault DECISION_PERSISTENCE '{"latencyMs":400,"errorRate":0}'
load 60 45s & L=$!
sleep 25
prom 'max(hikaricp_connections_active)' > "$EV/hikari-active.txt"
prom 'max(hikaricp_connections_pending)' > "$EV/hikari-pending.txt"
prom 'sum(increase(hikaricp_connections_timeout_total[1m]))' > "$EV/hikari-timeouts.txt"
sql -c "SELECT state, wait_event_type, count(*) FROM pg_stat_activity WHERE datname='riskplatform' AND application_name='decision-service' GROUP BY 1,2 ORDER BY 3 DESC" > "$EV/pg_stat_activity.txt"
docker exec $DS jcmd 1 Thread.dump_to_file -format=json -overwrite /dumps/ts02.json > /dev/null
cp "$LAB_ROOT/data/runtime/dumps/decision-service/ts02.json" "$EV/virtual-threads.json" 2>/dev/null
wait $L
note "FIX: remove the slow statement (lab fault cleared)"
lab_clear
cat "$EV/k6.txt" >> "$EV/timeline.txt"
python - "$EVW" <<'PY' | tee -a "$EV/timeline.txt"
import json, sys, collections, os
p = os.path.join(sys.argv[1], 'virtual-threads.json')
if os.path.exists(p):
    d = json.load(open(p)); c = collections.Counter()
    for tc in d['threadDump']['threadContainers']:
        for t in tc.get('threads', []):
            st = t.get('stack') or []
            if any('HikariPool.getConnection' in f for f in st): c['waiting in HikariPool.getConnection'] += 1
            elif any('FaultInjector.apply' in f for f in st): c['holding a connection (slow statement)'] += 1
    print('virtual threads:', dict(c))
PY
tail -n +1 "$EV"/hikari-*.txt "$EV/pg_stat_activity.txt"
