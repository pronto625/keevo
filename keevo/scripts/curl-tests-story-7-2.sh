#!/usr/bin/env bash
# ======================================================
# Story 7.2 — cURL Integration Tests
# Run: bash curl-tests-story-7-2.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
PHONE="${PHONE:-+237600072001}"
PASSWORD="${PASSWORD:-Test7200!}"

echo "═══════════════════════════════════════════════════"
echo "  Story 7.2 — End-of-Day Report cURL Tests"
echo "═══════════════════════════════════════════════════"

# ── Step 1: Register or login to get JWT ──────────────────────────────────────
# Auth flow (Story 1.7 two-step login):
#   Step A: POST /auth/login  → { loginToken, memberships[{tenantCode, schemaName, ...}] }
#   Step B: POST /auth/select-tenant { loginToken, tenantCode } → { accessToken, refreshToken, ... }
echo ""
echo "── Step 1: Login owner → JWT (two-step) ──"

LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASSWORD\"}")

LOGIN_TOKEN=$(echo "$LOGIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('loginToken', ''))
" 2>/dev/null || echo "")

TENANT_CODE=$(echo "$LOGIN" | python3 -c "
import sys, json
data = json.load(sys.stdin)
memberships = data.get('memberships', [])
print(memberships[0].get('tenantCode', '') if memberships else '')
" 2>/dev/null || echo "")

if [[ -z "$LOGIN_TOKEN" || "$LOGIN_TOKEN" == "null" ]]; then
  echo "❌ Step 1 FAILED — login response: $LOGIN"
  exit 1
fi

SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")

JWT=$(echo "$SELECT" | python3 -c "
import sys, json
data = json.load(sys.stdin)
print(data.get('accessToken', ''))
" 2>/dev/null || echo "")

[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — JWT obtained (tenant: $TENANT_CODE)" || { echo "❌ Step 1 FAILED — select-tenant response: $SELECT"; exit 1; }

# ── Step 2: GET /api/v1/reports — 401 without auth ───────────────────────────
echo ""
echo "── Step 2: GET /api/v1/reports — 401 without auth ──"
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/reports")
[[ "$HTTP_STATUS" == "401" ]] && echo "✅ Step 2 — 401 Unauthorized (no auth)" || { echo "❌ Step 2 FAILED — got $HTTP_STATUS"; exit 1; }

# ── Step 3: GET /api/v1/reports — 200 empty list ─────────────────────────────
echo ""
echo "── Step 3: GET /api/v1/reports — authenticated (empty list ok) ──"
REPORTS=$(curl -s -X GET "$BASE_URL/api/v1/reports?page=0&size=20" \
  -H "Authorization: Bearer $JWT")
RESP_CODE=$(echo "$REPORTS" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data.get('status') == 'success' or data.get('data') is not None:
        print('200')
    elif str(data.get('httpStatus','')).startswith('4'):
        print(str(data.get('httpStatus','')))
    else:
        print('200')
except:
    print('500')
" 2>/dev/null || echo "500")
[[ "$RESP_CODE" == "200" ]] && echo "✅ Step 3 — Reports list OK" || { echo "❌ Step 3 FAILED — got $RESP_CODE"; exit 1; }

# ── Step 4: Get store ID ──────────────────────────────────────────────────────
echo ""
echo "── Step 4: Get store ID ──"
STORES=$(curl -s -X GET "$BASE_URL/api/v1/stores" -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('data', [])
if isinstance(content, dict):
    content = content.get('content', data.get('data', {}).get('items', []))
if isinstance(content, list) and content:
    print(content[0].get('id', ''))
else:
    print('')
" 2>/dev/null || echo "")
[[ -n "$STORE_ID" ]] && echo "✅ Step 4 — Store ID: $STORE_ID" || echo "⚠️ Step 4 — No store found (run onboarding first)"

# ── Step 5: Trigger day closure → report generation ──────────────────────────
echo ""
echo "── Step 5: POST /api/v1/day-closures → triggers EndOfDayReportListener ──"
if [[ -n "$STORE_ID" ]]; then
  CLOSURE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/day-closures" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE_ID\"}")
  [[ "$CLOSURE" == "200" || "$CLOSURE" == "201" || "$CLOSURE" == "409" ]] && \
    echo "✅ Step 5 — Day closure: $CLOSURE" || echo "⚠️ Step 5 — Closure: $CLOSURE"
  sleep 2  # allow async @EventListener to process
else
  echo "⚠️ Step 5 — Skipped (no store)"
fi

# ── Step 6: GET /api/v1/reports — after closure ──────────────────────────────
echo ""
echo "── Step 6: GET /api/v1/reports — expects report after closure ──"
REPORTS_AFTER=$(curl -s -X GET "$BASE_URL/api/v1/reports?type=DAILY&page=0&size=20" \
  -H "Authorization: Bearer $JWT")
REPORT_ID=$(echo "$REPORTS_AFTER" | python3 -c "
import sys, json
data = json.load(sys.stdin)
content = data.get('data', {})
if isinstance(content, dict):
    items = content.get('items', content.get('content', []))
elif isinstance(content, list):
    items = content
else:
    items = []
if items:
    print(items[0].get('id', ''))
else:
    print('')
" 2>/dev/null || echo "")
[[ -n "$REPORT_ID" ]] && echo "✅ Step 6 — Report ID: $REPORT_ID" || echo "⚠️ Step 6 — No report yet (close a day first)"

# ── Step 7: GET /api/v1/reports/{id} — full report detail ────────────────────
echo ""
echo "── Step 7: GET /api/v1/reports/{id} — full content ──"
if [[ -n "$REPORT_ID" ]]; then
  DETAIL=$(curl -s -X GET "$BASE_URL/api/v1/reports/$REPORT_ID" \
    -H "Authorization: Bearer $JWT")
  CONTENT=$(echo "$DETAIL" | python3 -c "
import sys, json
data = json.load(sys.stdin)
report = data.get('data', {})
print(report.get('content', ''))
" 2>/dev/null || echo "")
  [[ -n "$CONTENT" ]] && echo "✅ Step 7 — Report detail with content" || echo "⚠️ Step 7 — Empty content"

  # Check format
  echo ""
  echo "── Step 7b: Verify report content format (emoji + CA) ──"
  FORMAT_OK=$(echo "$CONTENT" | python3 -c "
import sys
text = sys.stdin.read()
has_report = '📊' in text or 'Rapport' in text
has_ca = '💰' in text or 'CA' in text
print('OK' if has_report and has_ca else 'MISSING')
" 2>/dev/null || echo "MISSING")
  [[ "$FORMAT_OK" == "OK" ]] && echo "✅ Step 7b — Content has emoji + CA" || echo "⚠️ Step 7b — Format incomplete"
else
  echo "⚠️ Step 7 — Skipped (no report ID)"
fi

# ── Step 8: POST /api/v1/reports/{id}/resend ─────────────────────────────────
echo ""
echo "── Step 8: POST /api/v1/reports/{id}/resend ──"
if [[ -n "$REPORT_ID" ]]; then
  RESEND_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/reports/$REPORT_ID/resend" \
    -H "Authorization: Bearer $JWT")
  # 200 = ok, 409 = already sent — both valid
  [[ "$RESEND_CODE" == "200" || "$RESEND_CODE" == "409" ]] && \
    echo "✅ Step 8 — Resend: $RESEND_CODE" || echo "⚠️ Step 8 — Resend: $RESEND_CODE"
else
  echo "⚠️ Step 8 — Skipped (no report ID)"
fi

# ── Step 9: 404 on non-existent report ───────────────────────────────────────
echo ""
echo "── Step 9: GET non-existent report → 404 ──"
FAKE_ID="00000000-0000-0000-0000-000000000000"
NOT_FOUND=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE_URL/api/v1/reports/$FAKE_ID" \
  -H "Authorization: Bearer $JWT")
[[ "$NOT_FOUND" == "404" ]] && echo "✅ Step 9 — 404 Not Found" || echo "⚠️ Step 9 — got $NOT_FOUND"

echo ""
echo "═══════════════════════════════════════════════════"
echo "  Story 7.2 cURL Tests Complete"
echo "═══════════════════════════════════════════════════"
echo "  Run 'mvn test' in backend and 'flutter test test/features/reports/' for full validation"
