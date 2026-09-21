#!/usr/bin/env bash
# TS-14 REVIEW spike: Aldermoor strategy 1.2.0 is meant to tighten the review threshold to 0.35 but ships 0.04 (typo).
# It passes schema validation and four-eyes approval, and goes out as a 25% canary. The canary cohort floods the
# review queue; the rollout percentage limits the blast radius; rollback removes the candidate.
source "$(dirname "$0")/lib.sh"; evidence ts-14
APPROVER='X-Api-Key: dev-aldermoor-approver-key'
mix() {
  sql -tAc "SELECT strategy_version, decision, count(*), round(100.0*count(*)/sum(count(*)) OVER (PARTITION BY strategy_version),1)||'%'
            FROM risk_decisions WHERE tenant_id='aldermoor-bank' AND created_at > '$1' GROUP BY 1,2 ORDER BY 1,2"
}
python -c "
import json; d=json.load(open(r'$WIN_ROOT/config/customers/aldermoor-bank/strategies/1.1.0.json', encoding='utf-8'))
d['version']='1.2.0'; d['thresholds']['default']['review']=0.04
d['changeSummary']='Tighten default review threshold for ATO campaign (intended 0.35)'
json.dump(d, open(r'$EVW/strategy-1.2.0.json','w', encoding='utf-8'), indent=1)"
sql -tAc "DELETE FROM strategy_versions WHERE tenant_id='aldermoor-bank' AND version='1.2.0'" > /dev/null   # re-runnable lab

note "CHANGE: author (aldermoor-admin) creates draft 1.2.0; approver (aldermoor-approver) approves"
curl -s -o "$EVW/draft.json" -w "create draft HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/strategies \
  -H "$ADMIN" -H 'Content-Type: application/json' --data-binary "@$EVW/strategy-1.2.0.json" | tee -a "$EV/timeline.txt"
curl -s -o "$EVW/self-approve.json" -w "self-approval by author: HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/strategies/1.2.0/approve \
  -H "$ADMIN" -H 'Content-Type: application/json' -d '{"comment":"lgtm"}' | tee -a "$EV/timeline.txt"
curl -s -o "$EVW/approve.json" -w "approval by second person: HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/strategies/1.2.0/approve \
  -H "$APPROVER" -H 'Content-Type: application/json' -d '{"comment":"threshold change for ATO campaign"}' | tee -a "$EV/timeline.txt"
python -c "import json; a=json.load(open(r'$EVW/approve.json', encoding='utf-8')); print('status:', a.get('status'), '| validation warnings:', a.get('warnings') or a.get('validation',{}).get('warnings'))" | tee -a "$EV/timeline.txt"

T1=$(now); note "INCIDENT: 1.2.0 promoted to dev as a 25% canary"
curl -s -o "$EVW/promote.json" -w "promote HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/deployments/dev/promote \
  -H "$ADMIN" -H 'Content-Type: application/json' -d '{"version":"1.2.0","rolloutPercentage":25,"reason":"ATO campaign"}' | tee -a "$EV/timeline.txt"
CASES0=$(sql -tAc "SELECT count(*) FROM fraud_cases WHERE tenant_id='aldermoor-bank'")
load 60 60s TENANT=aldermoor-bank
mix "$T1" | tee "$EV/mix-canary.txt" | tee -a "$EV/timeline.txt"
sleep 10
note "fraud_cases created during the canary minute: $(( $(sql -tAc "SELECT count(*) FROM fraud_cases WHERE tenant_id='aldermoor-bank'") - CASES0 ))"

T2=$(now); note "FIX: rollback (removes the candidate; 1.1.0 serves 100%)"
curl -s -o "$EVW/rollback.json" -w "rollback HTTP %{http_code}\n" -X POST localhost:8080/v1/admin/tenants/aldermoor-bank/deployments/dev/rollback \
  -H "$ADMIN" -H 'Content-Type: application/json' -d '{"reason":"TS-14 review spike in 1.2.0 canary cohort"}' | tee -a "$EV/timeline.txt"
cat "$EV/rollback.json" >> "$EV/timeline.txt"; echo >> "$EV/timeline.txt"
load 60 30s TENANT=aldermoor-bank
mix "$T2" | tee "$EV/mix-after-rollback.txt" | tee -a "$EV/timeline.txt"

note "PREVENTION: the paired simulation that should gate the canary"
curl -s -o "$EVW/simulate-1.2.0.json" -X POST "localhost:8080/v1/admin/tenants/aldermoor-bank/strategies/1.2.0/simulate?limit=5000" -H "$ADMIN"
python -c "
import json; r=json.load(open(r'$EVW/simulate-1.2.0.json', encoding='utf-8'))
print({k: r[k] for k in ('activeVersion','candidateVersion','evaluated','reviewRateActive','reviewRateCandidate','declineRateActive','declineRateCandidate')})" | tee -a "$EV/timeline.txt"
sql -tAc "SELECT created_at::time(0), actor, action, entity_id FROM audit_events
          WHERE tenant_id='aldermoor-bank' AND created_at > '$T1'::timestamptz - interval '2 minutes' ORDER BY created_at" | tee -a "$EV/timeline.txt"
