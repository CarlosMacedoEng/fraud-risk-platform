#!/usr/bin/env bash
# TS-17 Broken database migration, reproduced on a scratch database (riskplatform_ts17) with the Flyway CLI,
# then fixed with expand -> backfill -> contract. The real schema is never touched.
source "$(dirname "$0")/lib.sh"; evidence ts-17
export MSYS_NO_PATHCONV=1
DB=riskplatform_ts17
MIG="$LAB_ROOT/risk-platform/decision-service/src/main/resources/db/migration"
WORK="$EV/sql"; mkdir -p "$WORK"; WORKW="$EVW/sql"
flyway() { docker run --rm --network fraud-platform_default -v "$WORKW:/flyway/sql:ro" flyway/flyway:11-alpine \
             -url=jdbc:postgresql://postgres:5432/$DB -user=risk -password=risk "$@" 2>&1; }
psql_s() { docker exec -i fraud-platform-postgres-1 psql -U risk -d $DB -v ON_ERROR_STOP=1 "$@"; }

note "SETUP: scratch database with V1..V5 and a copy of 20,000 recent decisions"
sql -tAc "DROP DATABASE IF EXISTS $DB" >/dev/null; sql -tAc "CREATE DATABASE $DB" >/dev/null
cp "$MIG"/V[1-5]__*.sql "$WORK/"
flyway migrate | grep -E "Successfully applied|ERROR" | tee -a "$EV/timeline.txt"
sql -c "\copy (SELECT t.* FROM transactions t JOIN (SELECT tenant_id, transaction_id FROM risk_decisions ORDER BY created_at DESC LIMIT 20000) d USING (tenant_id, transaction_id)) TO STDOUT" \
  | psql_s -c "\copy transactions FROM STDIN" | tee -a "$EV/timeline.txt"
sql -c "\copy (SELECT * FROM risk_decisions ORDER BY created_at DESC LIMIT 20000) TO STDOUT" \
  | psql_s -c "\copy risk_decisions FROM STDIN" | tee -a "$EV/timeline.txt"

note "INCIDENT: release ships V6 (ADD COLUMN channel text NOT NULL) - tested only on an empty dev database"
cp "$LAB_ROOT/troubleshooting-lab/ts-17-migrations/broken/V6__decision_channel.sql" "$WORK/"
flyway migrate > "$EV/flyway-migrate-broken.txt"; echo "flyway exit code: $?" | tee -a "$EV/timeline.txt"
grep -E "ERROR|Message|SQL State|Migration V6|contains null" "$EV/flyway-migrate-broken.txt" | head -8 | tee -a "$EV/timeline.txt"
flyway info > "$EV/flyway-info-broken.txt"; grep -E "Versioned|\| [0-9]" "$EV/flyway-info-broken.txt" | tee -a "$EV/timeline.txt"
note "schema after failure: channel column present? $(psql_s -tAc "SELECT count(*) FROM information_schema.columns WHERE table_name='risk_decisions' AND column_name='channel'") (0 = rolled back: PostgreSQL DDL is transactional)"

note "FIX 1/3 expand: replace V6 with a nullable column (release not yet shipped anywhere, so V6 can be replaced)"
rm "$WORK/V6__decision_channel.sql"; cp "$LAB_ROOT/troubleshooting-lab/ts-17-migrations/fixed/V6__decision_channel_expand.sql" "$WORK/"
s=$(date +%s%N); flyway migrate | grep -E "Successfully applied|ERROR" | tee -a "$EV/timeline.txt"; note "V6 expand took $(( ($(date +%s%N)-s)/1000000 )) ms (incl. container start)"
note "FIX 2/3 backfill in batches of 5,000"
s=$(date +%s%N)
docker exec -i fraud-platform-postgres-1 psql -U risk -d $DB -v ON_ERROR_STOP=1 < "$LAB_ROOT/troubleshooting-lab/ts-17-migrations/fixed/backfill.sql" 2>&1 | tee -a "$EV/timeline.txt"
note "backfill took $(( ($(date +%s%N)-s)/1000000 )) ms; rows still NULL: $(psql_s -tAc "SELECT count(*) FROM risk_decisions WHERE channel IS NULL")"
note "FIX 3/3 contract: V7 NOT VALID -> VALIDATE -> SET NOT NULL"
cp "$LAB_ROOT/troubleshooting-lab/ts-17-migrations/fixed/V7__decision_channel_contract.sql" "$WORK/"
flyway migrate | grep -E "Successfully applied|ERROR" | tee -a "$EV/timeline.txt"
flyway info > "$EV/flyway-info-fixed.txt"; grep -E "\| [0-9]" "$EV/flyway-info-fixed.txt" | tee -a "$EV/timeline.txt"
psql_s -tAc "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name='risk_decisions' AND column_name='channel'" | tee -a "$EV/timeline.txt"
psql_s -tAc "SELECT channel, count(*) FROM risk_decisions GROUP BY 1 ORDER BY 2 DESC" | tee -a "$EV/timeline.txt"
sql -tAc "DROP DATABASE $DB" >/dev/null && note "scratch database dropped"
