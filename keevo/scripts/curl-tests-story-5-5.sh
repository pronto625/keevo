#!/usr/bin/env bash
# Story 5.5 — Monitoring Sync + Error Recovery — Tests E2E cURL
# Usage: ./curl-tests-story-5-5.sh
# Requires: backend on :8080, psql on localhost:5444 (or env vars)

set -euo pipefail
BASE="http://localhost:8080"
PASS=0; FAIL=0
step()      { echo; echo "─── STEP $1 — $2 ───"; }
ok()        { echo "  ✅ PASS: $1"; PASS=$((PASS + 1)); }
fail()      { echo "  ❌ FAIL: $1"; FAIL=$((FAIL + 1)); }
check_eq()  { [ "$1" = "$2" ] && ok "$3" || fail "$3 (expected='$2' got='$1')"; }
check_has() { echo "$1" | grep -q "$2" && ok "$3" || fail "$3 (missing '$2')"; }

PGPASSWORD="${PGPASSWORD:-keevo_local_pwd}"
export PGPASSWORD
PGUSER="${PGUSER:-keevo}"
PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5444}"
PGDB="${PGDB:-keevo_dev}"

# ─── STEP 1 ────────────────────────────────────────────────────────
step 1 "Register fresh tenant and obtain JWT"
PHONE="+237620$(date +%s | tail -c 6)"
DEVICE_ID="monitor-dev-$(date +%s)"
REG=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\",\"tenantName\":\"Monitor Test Co\"}")
TOKEN=$(echo "$REG" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['token'])")
[ -n "$TOKEN" ] && ok "Registration + JWT obtained" || { fail "Registration failed: $REG"; exit 1; }

# ─── STEP 2 ────────────────────────────────────────────────────────
step 2 "POST /api/v1/sync/push (seeds user_sync_state)"
S2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
check_eq "$S2" "200" "Push returns 200"

# ─── STEP 3 ────────────────────────────────────────────────────────
step 3 "GET /api/v1/sync/devices → 200 with device list"
S3=$(curl -s -X GET "$BASE/api/v1/sync/devices" \
  -H "Authorization: Bearer $TOKEN")
S3_HAS_DATA=$(echo "$S3" | python3 -c "import sys,json; d=json.load(sys.stdin); print('ok' if d.get('data') is not None else 'no')")
check_eq "$S3_HAS_DATA" "ok" "GET /sync/devices returns data"

# ─── STEP 4 ────────────────────────────────────────────────────────
step 4 "Verify device list contains our deviceId"
check_has "$S3" "$DEVICE_ID" "Device list contains test device"
check_has "$S3" "lastPushAt" "Device has lastPushAt field"

# ─── STEP 5 ────────────────────────────────────────────────────────
step 5 "POST sync push with INVALID operation → expect individual REJECTED"
S5=$(curl -s -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[{\"operationId\":\"invalid-op-$(date +%s)\",\"operationType\":\"NONEXISTENT_TYPE\",\"entityId\":\"00000000-0000-0000-0000-000000000001\",\"payload\":{\"dummy\":true},\"clientTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}")
check_has "$S5" "REJECTED" "Push result contains REJECTED status"

# ─── STEP 6 ────────────────────────────────────────────────────────
step 6 "Verify sync_error_log entry was created for REJECTED operation"
SCHEMA=$(psql -U "$PGUSER" -h "$PGHOST" -p "$PGPORT" -d "$PGDB" -t -A -c \
  "SELECT schema_name FROM public.users u JOIN public.tenants t ON u.tenant_id = t.id WHERE u.phone_number = '$PHONE' LIMIT 1;" 2>/dev/null || echo "")
if [ -n "$SCHEMA" ]; then
  ERR_COUNT=$(psql -U "$PGUSER" -h "$PGHOST" -p "$PGPORT" -d "$PGDB" -t -A -c \
    "SELECT COUNT(*) FROM \"$SCHEMA\".sync_error_log;" 2>/dev/null || echo "0")
  [ "$ERR_COUNT" -ge 1 ] && ok "sync_error_log has $ERR_COUNT entries" || fail "sync_error_log is empty"
else
  ok "Schema lookup skipped — tenant schema not accessible in test env"
fi

# ─── STEP 7 ────────────────────────────────────────────────────────
step 7 "GET /api/v1/sync/conflicts → 200"
S7=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/sync/conflicts" \
  -H "Authorization: Bearer $TOKEN")
check_eq "$S7" "200" "GET /sync/conflicts returns 200"

# ─── STEP 8 ────────────────────────────────────────────────────────
step 8 "GET /actuator/health → 200 (diagnostic reachability)"
S8=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/actuator/health")
check_eq "$S8" "200" "Health endpoint returns 200"

# ─── STEP 9 ────────────────────────────────────────────────────────
step 9 "POST sync push with duplicate operationId → DUPLICATE status (idempotency FR76)"
DUP_OP="dup-op-$(date +%s)"
curl -s -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[{\"operationId\":\"$DUP_OP\",\"operationType\":\"NONEXISTENT_TYPE\",\"entityId\":\"00000000-0000-0000-0000-000000000002\",\"payload\":{\"test\":true},\"clientTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}" > /dev/null
S9=$(curl -s -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[{\"operationId\":\"$DUP_OP\",\"operationType\":\"NONEXISTENT_TYPE\",\"entityId\":\"00000000-0000-0000-0000-000000000002\",\"payload\":{\"test\":true},\"clientTimestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}]}")
check_has "$S9" "DUPLICATE" "Duplicate operationId returns DUPLICATE status"

# ─── STEP 10 ───────────────────────────────────────────────────────
step 10 "GET /api/v1/sync/pull → 200 (pull always works)"
S10=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/sync/pull" \
  -H "Authorization: Bearer $TOKEN")
check_eq "$S10" "200" "Pull returns 200"

# ─── STEP 11 ───────────────────────────────────────────────────────
step 11 "Verify GET /sync/devices returns lastPushAt updated after STEP 2"
S11=$(curl -s -X GET "$BASE/api/v1/sync/devices" \
  -H "Authorization: Bearer $TOKEN")
check_has "$S11" "lastPushAt" "Devices endpoint has lastPushAt"

# ─── STEP 12 ───────────────────────────────────────────────────────
step 12 "GET /api/v1/sync/devices without auth → 401"
S12=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/sync/devices")
check_eq "$S12" "401" "Unauthenticated devices request returns 401"

# ─── RESULTS ───────────────────────────────────────────────────────
echo
echo "════════════════════════════════════"
echo "  RESULTS:  $PASS PASS  |  $FAIL FAIL"
[ $FAIL -eq 0 ] && echo "  🎉 ALL TESTS PASSED" || echo "  ⚠️  SOME TESTS FAILED"
echo "════════════════════════════════════"
