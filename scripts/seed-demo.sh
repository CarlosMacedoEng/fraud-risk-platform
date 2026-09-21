#!/usr/bin/env bash
# Seed the local stack through the real integration path: legacy sample files -> file-adapter -> Kafka ->
# decision-service consumers (customer replica, batch history, labels).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
INBOUND="$ROOT/data/runtime/files/inbound"
mkdir -p "$INBOUND"
for tenant in aldermoor-bank quillon-pay; do
  # Profiles first, then history, then labels (names sort CHARGEBACKS < CUSTOMER_PROFILES < FRAUD_LABELS < TXN_HISTORY,
  # which is fine: every consumer is independent).
  cp "$ROOT/data/samples/files/$tenant/"* "$INBOUND/"
done
curl -fsS -X POST -H 'X-Api-Key: dev-file-ops-key' http://localhost:8081/v1/ingestion/scan \
  | python -c "import sys,json; [print(f\"{r['report']['file']:60s} {r['status']:24s} {r['report'].get('eventsPublished','')}\") for r in json.load(sys.stdin)]"
echo "waiting for the customer replica..."
for i in $(seq 1 60); do
  n=$(docker exec fraud-platform-postgres-1 psql -U risk -d riskplatform -tAc "select count(*) from customers")
  [ "$n" -ge 7000 ] && break; sleep 2
done
docker exec fraud-platform-postgres-1 psql -U risk -d riskplatform -c \
  "select tenant_id, count(*) customers from customers group by 1 order by 1" -c \
  "select tenant_id, source, count(*) from transactions group by 1,2 order by 1,2" -c \
  "select tenant_id, source, label, count(*) from fraud_labels group by 1,2,3 order by 1,2,3"
