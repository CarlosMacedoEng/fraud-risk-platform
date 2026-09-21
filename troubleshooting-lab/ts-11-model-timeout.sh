#!/usr/bin/env bash
# TS-11 Model-service timeout (explanations) and in-process model inference exceeding its 25 ms budget.
source "$(dirname "$0")/lib.sh"; evidence ts-11
TX="TS11-$(date +%s)"
score decision "{\"transactionId\":\"$TX\",\"customerId\":\"ALD-C000321\",\"accountId\":\"ALD-A000321\",\"eventTime\":\"$(now)\",\"transactionType\":\"CARD_PAYMENT\",\"channel\":\"ECOM\",\"amount\":480.00,\"currency\":\"EUR\",\"cardToken\":\"tok_TS11\",\"merchantId\":\"M000100\",\"mcc\":\"5732\",\"merchantCountry\":\"PT\",\"deviceId\":\"D-TS11\",\"ipAddress\":\"11.2.3.4\",\"ipCountry\":\"PT\"}" > /dev/null
D=$(python -c "import json; print(json.load(open(r'$EVW/decision.json'))['decisionId'])")
note "INCIDENT A: model-service responds in 6 s (client deadline 4 s)"
curl -s -X POST localhost:8000/admin/faults -H 'Content-Type: application/json' -d '{"latency_ms":6000,"error_rate":0}' > /dev/null
curl -s -o "$EVW/explanation-degraded.json" -w "explanation endpoint: HTTP %{http_code} in %{time_total}s\n" \
  "localhost:8080/v1/decisions/$D/explanation" -H "$ANALYST" | tee -a "$EV/timeline.txt"
python -c "import json; e=json.load(open(r'$EVW/explanation-degraded.json')); print('shapAvailable=', e['shapAvailable'], '| note=', e['note'], '| stored reasons returned:', len(e['decisionReasons']))" | tee -a "$EV/timeline.txt"
curl -s -X POST localhost:8000/admin/faults -H 'Content-Type: application/json' -d '{"latency_ms":0,"error_rate":0}' > /dev/null
note "INCIDENT B: in-process inference slowed to 80 ms (budget 25 ms) at 30 rps"
lab_fault MODEL_INFERENCE '{"latencyMs":80,"errorRate":0}'
load 30 30s TENANT=aldermoor-bank
prom 'sum by (reason) (increase(risk_model_failures_total[1m]))' > "$EV/model-failures.txt"
prom 'sum by (decision) (increase(risk_decisions_total{tenant="aldermoor-bank"}[1m]))' > "$EV/decision-mix.txt"
lab_clear
note "FIX: fault cleared; decisions use the model again"
cat "$EV/k6.txt" >> "$EV/timeline.txt"
for f in model-failures decision-mix; do echo "== $f"; cat "$EV/$f.txt"; done | tee -a "$EV/timeline.txt"
