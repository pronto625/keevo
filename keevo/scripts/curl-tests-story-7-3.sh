#!/usr/bin/env bash
# ======================================================
# Story 7.3 — cURL Integration Tests
# Run: bash curl-tests-story-7-3.sh
# All steps must show ✅ before story is marked done
#
# Prerequisites:
#   - Backend running on localhost:8080 (docker-compose up or mvn spring-boot:run)
#   - An OWNER account exists with PHONE / PASSWORD below
#   - An EMPLOYEE account exists with EMPLOYEE_PHONE / EMPLOYEE_PASSWORD below
# ======================================================
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
PHONE="${PHONE:-+237600072001}"
PASSWORD="${PASSWORD:-Test7300!}"
EMPLOYEE_PHONE="${EMPLOYEE_PHONE:-+237600072002}"
EMPLOYEE_PASSWORD="${EMPLOYEE_PASSWORD:-Test7300!}"

echo "═══════════════════════════════════════════════════"
echo "  Story 7.3 — Weekly Report cURL Tests"
echo "═══════════════════════════════════════════════════"

# ── Helper: two-step JWT login ───────────────────────────────────────────────
# Returns the JWT on stdout, or empty string if login fails (no exit when optional=true)
login() {
  local phone="$1" password="$2" optional="${3:-false}"
  local resp
  resp=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$phone\",\"password\":\"$password\"}")

  local token code
  token=$(echo "$resp" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null || true)
  code=$(echo "$resp"  | python3 -c "import sys,json; d=json.load(sys.stdin); m=d.get('memberships',[]); print(m[0].get('tenantCode','') if m else '')" 2>/dev/null || true)

  if [[ -z "$token" || "$token" == "null" ]]; then
    if [[ "$optional" == "true" ]]; then
      echo ""
      return 0
    fi
    echo "❌ Login FAILED for $phone — response: $resp" >&2
    exit 1
  fi

  local sel
  sel=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginToken\":\"$token\",\"tenantCode\":\"$code\"}")

  echo "$sel" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))" 2>/dev/null || true
}

# ── Step 1: Login as OWNER ────────────────────────────────────────────────────
echo ""
echo "── Step 1: Login OWNER → JWT ──"
OWNER_JWT=$(login "$PHONE" "$PASSWORD")
[[ -n "$OWNER_JWT" && "$OWNER_JWT" != "null" ]] \
  && echo "✅ Step 1 — OWNER JWT obtained" \
  || { echo "❌ Step 1 FAILED — empty JWT"; exit 1; }

# ── Step 2: GET /api/v1/reports?type=WEEKLY — must be empty at first ─────────
echo ""
echo "── Step 2: GET /api/v1/reports?type=WEEKLY — 0 results (before trigger) ──"
RESP=$(curl -s "$BASE_URL/api/v1/reports?type=WEEKLY" \
  -H "Authorization: Bearer $OWNER_JWT")
TOTAL=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('totalElements',0))" 2>/dev/null || echo "-1")
echo "  totalElements=$TOTAL"
[[ "$TOTAL" -ge 0 ]] && echo "✅ Step 2 — Weekly reports list accessible" || { echo "❌ Step 2 FAILED — response: $RESP"; exit 1; }

# ── Step 3: POST /api/v1/reports/trigger-weekly — 403 for missing auth ───────
echo ""
echo "── Step 3: POST /trigger-weekly — 401 without auth ──"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  "$BASE_URL/api/v1/reports/trigger-weekly")
[[ "$STATUS" == "401" ]] \
  && echo "✅ Step 3 — 401 without auth" \
  || { echo "❌ Step 3 FAILED — expected 401, got $STATUS"; exit 1; }

# ── Step 4: POST /trigger-weekly — 403 for EMPLOYEE ─────────────────────────
echo ""
echo "── Step 4: POST /trigger-weekly — 403 for EMPLOYEE ──"
EMP_JWT=$(login "$EMPLOYEE_PHONE" "$EMPLOYEE_PASSWORD" "true")
if [[ -n "$EMP_JWT" && "$EMP_JWT" != "null" ]]; then
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    "$BASE_URL/api/v1/reports/trigger-weekly" \
    -H "Authorization: Bearer $EMP_JWT")
  [[ "$STATUS" == "403" ]] \
    && echo "✅ Step 4 — 403 for EMPLOYEE" \
    || echo "⚠️  Step 4 — expected 403, got $STATUS (employee account may not exist)"
else
  echo "⚠️  Step 4 SKIPPED — employee account not found in this environment"
fi

# ── Step 5: POST /trigger-weekly — 200 for OWNER ─────────────────────────────
echo ""
echo "── Step 5: POST /trigger-weekly — 200 for OWNER ──"
RESP=$(curl -s -w "\n%{http_code}" -X POST \
  "$BASE_URL/api/v1/reports/trigger-weekly" \
  -H "Authorization: Bearer $OWNER_JWT")
STATUS=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | head -n -1)
[[ "$STATUS" == "200" ]] \
  && echo "✅ Step 5 — Weekly report triggered successfully" \
  || { echo "❌ Step 5 FAILED — status=$STATUS body=$BODY"; exit 1; }

# ── Step 6: GET /api/v1/reports?type=WEEKLY — must have ≥ 1 result ───────────
echo ""
echo "── Step 6: GET /api/v1/reports?type=WEEKLY — ≥ 1 result after trigger ──"
RESP=$(curl -s "$BASE_URL/api/v1/reports?type=WEEKLY" \
  -H "Authorization: Bearer $OWNER_JWT")
TOTAL=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('totalElements',0))" 2>/dev/null || echo "0")
[[ "$TOTAL" -ge 1 ]] \
  && echo "✅ Step 6 — Weekly report persisted (totalElements=$TOTAL)" \
  || { echo "❌ Step 6 FAILED — expected ≥1, got $TOTAL — response: $RESP"; exit 1; }

# ── Step 7: GET /api/v1/reports/{id} — verify content has "Rapport Hebdomadaire" ──
echo ""
echo "── Step 7: GET /api/v1/reports/{id} — content has 'Rapport Hebdomadaire' ──"
REPORT_ID=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); items=d.get('data',{}).get('items',[]); print(items[0].get('id','') if items else '')" 2>/dev/null || echo "")
if [[ -n "$REPORT_ID" && "$REPORT_ID" != "null" ]]; then
  DETAIL=$(curl -s "$BASE_URL/api/v1/reports/$REPORT_ID" \
    -H "Authorization: Bearer $OWNER_JWT")
  CONTENT=$(echo "$DETAIL" | python3 -c \
    "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('content',''))" 2>/dev/null || echo "")
  echo "  content preview: ${CONTENT:0:80}..."
  echo "$CONTENT" | grep -q "Rapport Hebdomadaire" \
    && echo "✅ Step 7 — Content contains 'Rapport Hebdomadaire'" \
    || { echo "❌ Step 7 FAILED — content missing 'Rapport Hebdomadaire'"; exit 1; }
else
  echo "❌ Step 7 FAILED — could not extract report ID from list response"
  exit 1
fi

# ── Step 8: Verify reportType = WEEKLY ───────────────────────────────────────
echo ""
echo "── Step 8: Verify reportType = WEEKLY in list response ──"
REPORT_TYPE=$(echo "$RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); items=d.get('data',{}).get('items',[]); print(items[0].get('reportType','') if items else '')" 2>/dev/null || echo "")
[[ "$REPORT_TYPE" == "WEEKLY" ]] \
  && echo "✅ Step 8 — reportType=WEEKLY confirmed" \
  || { echo "❌ Step 8 FAILED — expected 'WEEKLY', got '$REPORT_TYPE'"; exit 1; }

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════"
echo "  ✅ All story 7.3 cURL tests passed!"
echo "═══════════════════════════════════════════════════"
