#!/usr/bin/env bash
# TS-09 Malformed input file (decimal commas, missing timestamps) and TS-10 schema mismatch (swapped columns).
source "$(dirname "$0")/lib.sh"
SAMPLES="$LAB_ROOT/data/samples/files/troubleshooting/aldermoor-bank"
INBOUND="$LAB_ROOT/data/runtime/files/inbound"
for spec in "ts-09:902" "ts-10:901"; do
  id=${spec%%:*}; seq=${spec##*:}
  evidence "$id"
  f=$(ls "$SAMPLES" | grep "_${seq}\.csv$")
  note "INCIDENT: core banking delivers $f"
  cp "$SAMPLES/$f" "$SAMPLES/$f.done" "$INBOUND/"
  curl -s -o "$EVW/scan.json" -X POST localhost:8081/v1/ingestion/scan -H 'X-Api-Key: dev-file-ops-key'
  cp "$LAB_ROOT/data/runtime/files/reports/$f.report.json" "$EV/report.json" 2>/dev/null
  cp "$LAB_ROOT/data/runtime/files/quarantine/$f.quarantine.jsonl" "$EV/" 2>/dev/null
  python -c "
import json
for r in json.load(open(r'$EVW/scan.json',encoding='utf-8')):
    print('status:', r.get('status'), '| reason:', r.get('reason'))
    rep=r.get('report'); rep=json.loads(rep) if isinstance(rep,str) else rep
    print(json.dumps(rep, indent=1)[:1500])" | tee -a "$EV/timeline.txt"
  ls "$LAB_ROOT/data/runtime/files/rejected" 2>/dev/null | grep -q "$f" && note "file moved to rejected/, nothing published"
done
