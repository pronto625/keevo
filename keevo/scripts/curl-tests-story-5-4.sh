#!/usr/bin/env bash
# Story 5.4 — Gate 7 jours offline — Tests E2E cURL
# Usage: ./curl-tests-story-5-4.sh
# Requires: backend on :8080, psql accessible (PGPASSWORD/PGUSER/PGPORT/PGDATABASE env vars)

set -euo pipefail
BASE="http://localhost:8080"
PASS=0; FAIL=0

step()      { echo; echo "─── STEP $1 — $2 ───"; }
ok()        { echo "  ✅ PASS: $1"; ((PASS++)); }
fail()      { echo "  ❌ FAIL: $1"; ((FAIL++)); }
check_eq()  { [ "$1" = "$2" ] && ok "$3" || fail "$3 (expected='$2' got='$1')"; }
check_has() { echo "$1" | grep -q "$2" && ok "$3" || fail "$3 (missing '$2')"; }

# ─── STEP 1: Register fresh tenant, obtain JWT ────────────────────────────────
step 1 "Register fresh tenant and obtain JWT"
PHONE="+237610$(date +%s | tail -c 6)"
DEVICE_ID="test-device-$(date +%s)"
REGISTER=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\",\"tenantName\":\"Gate Test Co\"}")
TOKEN=$(echo "$REGISTER" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['data']['accessToken'])")
[ -n "$TOKEN" ] && ok "Registration + JWT obtained" || { fail "Registration failed: $REGISTER"; exit 1; }

# ─── STEP 2: First push — expect 200 ──────────────────────────────────────────
step 2 "POST /api/v1/sync/push (fresh device) → expect 200"
PUSH1=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
check_eq "$PUSH1" "200" "First push returns 200"

# ─── STEP 3: Verify user_sync_state record created ────────────────────────────
step 3 "Verify public.user_sync_state record was created in DB"
DB_COUNT=$(PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
  -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -t -c \
  "SELECT COUNT(*) FROM public.user_sync_state WHERE device_id = '$DEVICE_ID';" | tr -d ' ')
check_eq "$DB_COUNT" "1" "user_sync_state record exists for test device"

# ─── STEP 4: Simulate stale device (8 days old last_push_at) ─────────────────
step 4 "Inject stale last_push_at = NOW() - INTERVAL '8 days'"
PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
  -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -c \
  "UPDATE public.user_sync_state SET last_push_at = NOW() - INTERVAL '8 days' WHERE device_id = '$DEVICE_ID';" \
  > /dev/null
ok "last_push_at set to 8 days ago"

# ─── STEP 5: Push with stale device → expect 423 ──────────────────────────────
step 5 "POST /api/v1/sync/push (stale 8-day-old device) → expect 423"
PUSH2_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
check_eq "$PUSH2_STATUS" "423" "Stale device push returns 423"

# ─── STEP 6: Verify 423 response body structure ────────────────────────────────
step 6 "Verify 423 response body: domainCode=SYNC_REQUIRED + detail fields"
PUSH2_BODY=$(curl -s -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
check_has "$PUSH2_BODY" "SYNC_REQUIRED"      "Response has domainCode SYNC_REQUIRED"
check_has "$PUSH2_BODY" "daysSinceLastSync"  "Response has daysSinceLastSync field"
check_has "$PUSH2_BODY" "lastPushAt"         "Response has lastPushAt field"

# ─── STEP 7: Reset last_push_at to NOW ────────────────────────────────────────
step 7 "Reset last_push_at to NOW() (simulate successful sync)"
PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
  -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -c \
  "UPDATE public.user_sync_state SET last_push_at = NOW() WHERE device_id = '$DEVICE_ID';" \
  > /dev/null
ok "last_push_at reset to NOW"

# ─── STEP 8: Push again after reset → expect 200 ──────────────────────────────
step 8 "POST /api/v1/sync/push after reset → expect 200 (gate lifted)"
PUSH3=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/sync/push" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"operations\":[]}")
check_eq "$PUSH3" "200" "Push after reset returns 200 (gate lifted)"

# ─── STEP 9: Pull is NOT gated — always 200 ───────────────────────────────────
step 9 "GET /api/v1/sync/pull → always 200 (pull not subject to 7-day gate)"
PULL=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/sync/pull" \
  -H "Authorization: Bearer $TOKEN")
check_eq "$PULL" "200" "Pull returns 200 regardless of device sync age"

# ─── STEP 10: Verify last_push_at was refreshed after STEP 8 ──────────────────
step 10 "Verify last_push_at updated in DB after successful push (STEP 8)"
SECS_AGO=$(PGPASSWORD="${PGPASSWORD:-postgres}" psql -U "${PGUSER:-postgres}" \
  -h localhost -p "${PGPORT:-5432}" -d "${PGDATABASE:-keevo}" -t -c \
  "SELECT EXTRACT(EPOCH FROM (NOW() - last_push_at))::int \
   FROM public.user_sync_state WHERE device_id = '$DEVICE_ID';" | tr -d ' ')
[ "$SECS_AGO" -lt 60 ] \
  && ok "last_push_at refreshed recently (${SECS_AGO}s ago)" \
  || fail "last_push_at NOT refreshed — still ${SECS_AGO}s ago"

# ─── RESULTS ──────────────────────────────────────────────────────────────────
echo
echo "════════════════════════════════════"
echo "  RESULTS:  $PASS PASS  |  $FAIL FAIL"
[ $FAIL -eq 0 ] && echo "  🎉 ALL 10 TESTS PASSED" || echo "  ⚠️  SOME TESTS FAILED"
echo "════════════════════════════════════"
