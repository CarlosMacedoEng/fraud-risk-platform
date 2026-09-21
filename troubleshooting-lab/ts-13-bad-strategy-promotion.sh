#!/usr/bin/env bash
# TS-13 Wrong strategy version promoted: Quillon Pay's superseded strategy 1.0.0 (its decline threshold caused false
# declines in the offline evaluation) is re-activated in dev at 100%. Detect the decline spike, roll back, audit.
source "$(dirname "$0")/lib.sh"; evidence ts-13
QA='X-Api-Key: dev-quillon-admin-key'
mix() {  # decision mix per strategy version since $1
  sql -tAc "SELECT strategy_version, decision, count(*), round(100.0*count(*)/sum(count(*)) OVER (PARTITION BY strategy_version),1)||'%'
            FROM risk_decisions WHERE tenant_id='quillon-pay' AND created_at > '$1' GROUP BY 1,2 ORDER BY 1,2"
}
T0=$(now); note "BASELINE: 1.1.0 active, 40 rps for 40 s"
load 40 40s TENANT=quillon-pay; mix "$T0" | tee "$EV/mix-baseline.txt" | tee -a "$EV/timeline.txt"

note "PREVENTION CHECK THAT WAS SKIPPED: simulate 1.0.0 against recent traffic"
curl -s -o "$EVW/simulate-1.0.0.json" -X POST "localhost:8080/v1/admin/tenants/quillon-pay/strategies/1.0.0/simulate?limit=5000" -H "$QA"
python -c "
import json; r=json.load(open(r'$EVW/simulate-1.0.0.json', encoding='utf-8'))
print({k: r[k] for k in r if k != 'sampleChanges'})" | tee -a "$EV/timeline.txt"

T1=$(now); note "INCIDENT: 1.0.0 promoted to dev at 100% (reason given: 'restore previous behaviour')"
curl -s -o "$EVW/promote.json" -w "promote HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/quillon-pay/deployments/dev/promote \
  -H "$QA" -H 'Content-Type: application/json' -d '{"version":"1.0.0","rolloutPercentage":100,"reason":"restore previous behaviour"}' | tee -a "$EV/timeline.txt"
load 40 60s TENANT=quillon-pay
mix "$T1" | tee "$EV/mix-incident.txt" | tee -a "$EV/timeline.txt"
prom 'sum by (decision) (increase(risk_decisions_total{tenant="quillon-pay"}[1m]))' > "$EV/prom-decisions-incident.txt"

T2=$(now); note "FIX: rollback dev -> previous version"
curl -s -o "$EVW/rollback.json" -w "rollback HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/quillon-pay/deployments/dev/rollback \
  -H "$QA" -H 'Content-Type: application/json' -d '{"reason":"TS-13 decline spike after 1.0.0 promotion"}' | tee -a "$EV/timeline.txt"
cat "$EV/rollback.json" >> "$EV/timeline.txt"; echo >> "$EV/timeline.txt"
load 40 40s TENANT=quillon-pay
mix "$T2" | tee "$EV/mix-after-rollback.txt" | tee -a "$EV/timeline.txt"

note "AUDIT TRAIL"
sql -tAc "SELECT created_at::time(0), actor, action, entity_id, coalesce(after_state::text,'') FROM audit_events
          WHERE tenant_id='quillon-pay' AND created_at > '$T1' ORDER BY created_at" | cut -c1-260 | tee -a "$EV/timeline.txt"
