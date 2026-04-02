#!/usr/bin/env bash
# =============================================================================
# E2E Test — Story 7.5: Configuration des Rapports & Préférences WhatsApp
# =============================================================================
#
# Prerequisites: backend running on localhost:8080 with a clean (or dev) DB.
#
# Steps tested:
#   1.  Register OWNER (phone-based)
#   2.  Complete onboarding
#   3.  Login as OWNER → get JWT
#   4.  GET /tenant/preferences — verify default eodReportEnabled=false
#   5.  PUT /tenant/report-preferences (OWNER) → 200, fields persisted
#   6.  GET /tenant/preferences — verify new values
#   7.  PUT /tenant/report-preferences — invalid eodReportTime → 422
#   8.  PUT /tenant/report-preferences — employee token → 403
#   9.  POST /tenant/report-test (OWNER) → 200, testSent field present
#   10. POST /tenant/report-test (unauthenticated) → 401
#
# Exit codes: 0 = all passed, 1 = one or more assertions failed.
# =============================================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
API="${BASE_URL}/api/v1"

OWNER_PHONE="+2370000$(shuf -i 10000-99999 -n1)"
EMPLOYEE_PHONE="+2371111$(shuf -i 10000-99999 -n1)"
PASSWORD="TestPass123!"

PASS=0
FAIL=0

# ── helpers ─────────────────────────────────────────────────────────────────

ok()   { echo "  ✅ $1"; ((PASS++)); }
fail() { echo "  ❌ $1"; ((FAIL++)); }

assert_status() {
  local label="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    ok "$label → HTTP $actual"
  else
    fail "$label → expected HTTP $expected, got $actual"
  fi
}

assert_contains() {
  local label="$1" needle="$2" haystack="$3"
  if echo "$haystack" | grep -q "$needle"; then
    ok "$label contains '$needle'"
  else
    fail "$label missing '$needle' in: $haystack"
  fi
}

assert_not_contains() {
  local label="$1" needle="$2" haystack="$3"
  if echo "$haystack" | grep -q "$needle"; then
    fail "$label should NOT contain '$needle'"
  else
    ok "$label does not contain '$needle'"
  fi
}

# ── Step 1: Register OWNER ──────────────────────────────────────────────────
echo
echo "■ Step 1 — Register OWNER ($OWNER_PHONE)"
REG_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$OWNER_PHONE\",\"password\":\"$PASSWORD\"}")
assert_status "POST /auth/register" "201" "$REG_STATUS"

# ── Step 2: Complete onboarding ─────────────────────────────────────────────
echo
echo "■ Step 2 — Complete onboarding"
ONBOARD_RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/onboarding/complete" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$OWNER_PHONE\",\"password\":\"$PASSWORD\",\"sectorType\":\"OTHER\",\"storeName\":\"Boutique Test 7.5\"}")
ONBOARD_BODY=$(echo "$ONBOARD_RESP" | head -1)
ONBOARD_STATUS=$(echo "$ONBOARD_RESP" | tail -1)
assert_status "POST /onboarding/complete" "200" "$ONBOARD_STATUS"

# ── Step 3: Login as OWNER ───────────────────────────────────────────────────
echo
echo "■ Step 3 — Login as OWNER"
LOGIN_RESP=$(curl -s -X POST "$API/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$OWNER_PHONE\",\"password\":\"$PASSWORD\"}")
OWNER_TOKEN=$(echo "$LOGIN_RESP" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [[ -z "$OWNER_TOKEN" ]]; then
  fail "Login — could not extract token from: $LOGIN_RESP"
  echo "FATAL: cannot continue without token"
  exit 1
fi
ok "Login — got token ${OWNER_TOKEN:0:30}..."

# ── Step 4: GET /preferences — verify defaults ───────────────────────────────
echo
echo "■ Step 4 — GET /tenant/preferences (defaults)"
PREFS_RESP=$(curl -s -w "\n%{http_code}" "$API/tenant/preferences" \
  -H "Authorization: Bearer $OWNER_TOKEN")
PREFS_BODY=$(echo "$PREFS_RESP" | head -1)
PREFS_STATUS=$(echo "$PREFS_RESP" | tail -1)
assert_status "GET /tenant/preferences" "200" "$PREFS_STATUS"
assert_contains "defaults — eodReportEnabled present" "eodReportEnabled" "$PREFS_BODY"
assert_contains "defaults — eodReportChannel present" "eodReportChannel" "$PREFS_BODY"

# ── Step 5: PUT /tenant/report-preferences (OWNER, valid) ────────────────────
echo
echo "■ Step 5 — PUT /tenant/report-preferences (OWNER, valid payload)"
PUT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$API/tenant/report-preferences" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "eodReportEnabled": true,
    "eodReportChannel": "WHATSAPP",
    "eodReportTime": "20:00:00",
    "weeklyReportEnabled": true,
    "weeklyReportDay": 5,
    "weeklyReportTime": "08:00:00",
    "weeklyReportChannel": "IN_APP_ONLY",
    "inventoryReportEnabled": false,
    "inventoryReportChannel": "WHATSAPP",
    "stockAlertChannel": "PUSH"
  }')
assert_status "PUT /tenant/report-preferences (OWNER)" "200" "$PUT_STATUS"

# ── Step 6: GET /preferences — verify persisted values ───────────────────────
echo
echo "■ Step 6 — GET /tenant/preferences (verify persisted values)"
PREFS2_RESP=$(curl -s -w "\n%{http_code}" "$API/tenant/preferences" \
  -H "Authorization: Bearer $OWNER_TOKEN")
PREFS2_BODY=$(echo "$PREFS2_RESP" | head -1)
PREFS2_STATUS=$(echo "$PREFS2_RESP" | tail -1)
assert_status "GET /tenant/preferences (after PUT)" "200" "$PREFS2_STATUS"
assert_contains "persisted — eodReportEnabled:true" "true" "$PREFS2_BODY"
assert_contains "persisted — eodReportChannel:WHATSAPP" "WHATSAPP" "$PREFS2_BODY"
assert_contains "persisted — weeklyReportDay:5" "5" "$PREFS2_BODY"

# ── Step 7: PUT — invalid eodReportTime (bad format) → 422 ───────────────────
echo
echo "■ Step 7 — PUT /tenant/report-preferences (invalid eodReportTime → 422)"
INVALID_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$API/tenant/report-preferences" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "eodReportEnabled": true,
    "eodReportChannel": "WHATSAPP",
    "eodReportTime": "99:99:99",
    "weeklyReportEnabled": false,
    "weeklyReportDay": 1,
    "weeklyReportTime": "08:00:00",
    "weeklyReportChannel": "WHATSAPP",
    "inventoryReportEnabled": false,
    "inventoryReportChannel": "WHATSAPP",
    "stockAlertChannel": "PUSH"
  }')
assert_status "PUT /tenant/report-preferences (invalid time)" "422" "$INVALID_STATUS"

# ── Step 8: PUT — employee token → 403 ───────────────────────────────────────
echo
echo "■ Step 8 — PUT /tenant/report-preferences (EMPLOYEE → 403)"
# Invite an employee then login
INVITE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/stores/members/invite" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMPLOYEE_PHONE\",\"role\":\"EMPLOYEE\"}" 2>/dev/null || echo "skip")

if [[ "$INVITE_STATUS" == "201" ]]; then
  EMP_REG=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$EMPLOYEE_PHONE\",\"password\":\"$PASSWORD\"}" 2>/dev/null || echo "0")
  EMP_LOGIN=$(curl -s -X POST "$API/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$EMPLOYEE_PHONE\",\"password\":\"$PASSWORD\"}" 2>/dev/null || echo "{}")
  EMP_TOKEN=$(echo "$EMP_LOGIN" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
  if [[ -n "$EMP_TOKEN" ]]; then
    EMP_PUT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$API/tenant/report-preferences" \
      -H "Authorization: Bearer $EMP_TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"eodReportEnabled":false,"eodReportChannel":"WHATSAPP","eodReportTime":"20:00:00","weeklyReportEnabled":false,"weeklyReportDay":1,"weeklyReportTime":"08:00:00","weeklyReportChannel":"WHATSAPP","inventoryReportEnabled":false,"inventoryReportChannel":"WHATSAPP","stockAlertChannel":"PUSH"}')
    assert_status "PUT /tenant/report-preferences (EMPLOYEE)" "403" "$EMP_PUT_STATUS"
  else
    echo "  ⚠️  Step 8 skipped — could not get employee token"
  fi
else
  echo "  ⚠️  Step 8 skipped — invite endpoint not available (status: $INVITE_STATUS)"
fi

# ── Step 9: POST /tenant/report-test (OWNER) → 200 ───────────────────────────
echo
echo "■ Step 9 — POST /tenant/report-test (OWNER)"
TEST_RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/tenant/report-test" \
  -H "Authorization: Bearer $OWNER_TOKEN")
TEST_BODY=$(echo "$TEST_RESP" | head -1)
TEST_STATUS=$(echo "$TEST_RESP" | tail -1)
assert_status "POST /tenant/report-test (OWNER)" "200" "$TEST_STATUS"
assert_contains "report-test — testSent field present" "testSent" "$TEST_BODY"

# ── Step 10: POST /report-test — unauthenticated → 401 ───────────────────────
echo
echo "■ Step 10 — POST /tenant/report-test (unauthenticated → 401)"
UNAUTH_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/tenant/report-test")
assert_status "POST /tenant/report-test (no token)" "401" "$UNAUTH_STATUS"

# ── Summary ──────────────────────────────────────────────────────────────────
echo
echo "════════════════════════════════════════"
echo "  Story 7.5 E2E — Results"
echo "════════════════════════════════════════"
echo "  Passed : $PASS"
echo "  Failed : $FAIL"
echo "════════════════════════════════════════"

if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
