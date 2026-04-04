#!/usr/bin/env bash
# ======================================================
# Story 8.0 — cURL Integration Tests
# FCM Push Notifications + WhatsApp Wassender
# Run: bash curl-tests-story-8-0.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail

BASE_URL="http://localhost:8080"
PHONE="+237600080001"
PASSWORD="Test8000!"

echo "═══════════════════════════════════════════════════"
echo "  Story 8.0 — Messaging / Device Tokens cURL Tests"
echo "═══════════════════════════════════════════════════"

# ─────────────────────────────────────────────────────────
# STEP 0 — Register user + login + select-tenant
# ─────────────────────────────────────────────────────────
echo ""
echo "── Step 0 — Register + Auth ──"

REG_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"phoneNumber\":\"$PHONE\",
    \"password\":\"$PASSWORD\",
    \"fullName\":\"Test Story 8.0\",
    \"businessName\":\"FCM Test Shop\",
    \"sector\":\"RETAIL\"
  }")

echo "  Register response: $(echo "$REG_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print('OK' if d.get('accessToken') or d.get('data',{}).get('accessToken') else 'see details')" 2>/dev/null || echo 'raw')"

# If registration fails (likely 409 if already exists), fall back to login
TOKEN=$(echo "$REG_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('data',{}).get('accessToken',''))" 2>/dev/null || true)

if [[ -z "$TOKEN" || "$TOKEN" == "null" || "$TOKEN" == "None" ]]; then
  echo "  Registration returned no token — trying login flow..."

  LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASSWORD\"}")

  PRE_TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('loginToken',''))" 2>/dev/null || true)
  TENANT_CODE=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; m=json.load(sys.stdin).get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null || true)

  if [[ -z "$PRE_TOKEN" || "$PRE_TOKEN" == "null" ]]; then
    echo "  ❌ FAILED — no loginToken"
    echo "  RAW: $LOGIN_RESP"
    exit 1
  fi

  TENANT_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginToken\":\"$PRE_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")

  TOKEN=$(echo "$TENANT_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('data',{}).get('accessToken',''))" 2>/dev/null || true)
fi

if [[ -z "$TOKEN" || "$TOKEN" == "null" || "$TOKEN" == "None" ]]; then
  echo "  ❌ FAILED — could not obtain JWT token"
  exit 1
fi

echo "  ✅ Step 0 — Token obtained (${#TOKEN} chars)"

AUTH="Authorization: Bearer $TOKEN"

# ─────────────────────────────────────────────────────────
# TEST 1 — GET /api/v1/messaging/status (200)
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 1 — GET /messaging/status → 200 ──"

RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/messaging/status" \
  -H "$AUTH")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
echo "  $BODY" | python3 -m json.tool 2>/dev/null || echo "  $BODY"

if [[ "$HTTP" == "200" ]]; then
  # Verify expected fields exist
  HAS_WA=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print('whatsappProvider' in d.get('data',d))" 2>/dev/null || echo "False")
  HAS_FCM=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print('fcmEnabled' in d.get('data',d))" 2>/dev/null || echo "False")
  if [[ "$HAS_WA" == "True" && "$HAS_FCM" == "True" ]]; then
    echo "  ✅ Test 1 — 200 OK, contains whatsappProvider + fcmEnabled"
  else
    echo "  ⚠️  Test 1 — 200 OK but missing expected fields (WA=$HAS_WA, FCM=$HAS_FCM)"
  fi
else
  echo "  ❌ Test 1 FAILED — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 2 — GET /messaging/status without auth → 401
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 2 — GET /messaging/status (no auth) → 401 ──"

RESP=$(curl -s -w "\n%{http_code}" "$BASE_URL/api/v1/messaging/status")
HTTP=$(echo "$RESP" | tail -1)

echo "  HTTP $HTTP"

if [[ "$HTTP" == "401" ]]; then
  echo "  ✅ Test 2 — 401 Unauthorized (no token)"
else
  echo "  ❌ Test 2 FAILED — expected 401, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 3 — POST /api/v1/devices/token → register device
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 3 — POST /devices/token → register FCM token ──"

FCM_TOKEN="faketoken_story80_$(date +%s)"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{
    \"token\":\"$FCM_TOKEN\",
    \"platform\":\"ANDROID\",
    \"deviceName\":\"Curl Test Device\"
  }")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
echo "  $BODY" | python3 -m json.tool 2>/dev/null || echo "  $BODY"

if [[ "$HTTP" == "200" ]]; then
  REGISTERED=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',d).get('registered', False))" 2>/dev/null || echo "False")
  if [[ "$REGISTERED" == "True" ]]; then
    echo "  ✅ Test 3 — 200 OK, registered=true"
  else
    echo "  ⚠️  Test 3 — 200 OK but registered field not True"
  fi
else
  echo "  ❌ Test 3 FAILED — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 4 — POST /devices/token with blank token → 400
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 4 — POST /devices/token (blank token) → 422 ──"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{
    \"token\":\"\",
    \"platform\":\"ANDROID\",
    \"deviceName\":\"Bad Device\"
  }")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
echo "  $BODY" | python3 -m json.tool 2>/dev/null || echo "  $BODY"

if [[ "$HTTP" == "422" ]]; then
  echo "  ✅ Test 4 — 422 Unprocessable Entity (blank token rejected)"
else
  echo "  ❌ Test 4 FAILED — expected 422, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 5 — POST /devices/token with missing platform → 400
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 5 — POST /devices/token (missing platform) → 422 ──"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{
    \"token\":\"some-token\",
    \"platform\":\"\",
    \"deviceName\":\"Bad Device\"
  }")
HTTP=$(echo "$RESP" | tail -1)

echo "  HTTP $HTTP"

if [[ "$HTTP" == "422" ]]; then
  echo "  ✅ Test 5 — 422 Unprocessable Entity (blank platform rejected)"
else
  echo "  ❌ Test 5 FAILED — expected 422, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 6 — POST /devices/token without auth → 401
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 6 — POST /devices/token (no auth) → 401 ──"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"no-auth-token\",\"platform\":\"ANDROID\"}")
HTTP=$(echo "$RESP" | tail -1)

echo "  HTTP $HTTP"

if [[ "$HTTP" == "401" ]]; then
  echo "  ✅ Test 6 — 401 Unauthorized"
else
  echo "  ❌ Test 6 FAILED — expected 401, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 7 — POST /devices/token again (idempotent upsert)
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 7 — POST /devices/token (same token, idempotent) → 200 ──"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{
    \"token\":\"$FCM_TOKEN\",
    \"platform\":\"ANDROID\",
    \"deviceName\":\"Updated Device Name\"
  }")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"

if [[ "$HTTP" == "200" ]]; then
  echo "  ✅ Test 7 — 200 OK, re-registration (upsert) works"
else
  echo "  ❌ Test 7 FAILED — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 8 — DELETE /api/v1/devices/token → remove device
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 8 — DELETE /devices/token → remove registered token ──"

RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"$FCM_TOKEN\"}")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
echo "  $BODY" | python3 -m json.tool 2>/dev/null || echo "  $BODY"

if [[ "$HTTP" == "200" ]]; then
  DELETED=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',d).get('deleted', False))" 2>/dev/null || echo "False")
  if [[ "$DELETED" == "True" ]]; then
    echo "  ✅ Test 8 — 200 OK, deleted=true"
  else
    echo "  ⚠️  Test 8 — 200 OK but deleted field not True"
  fi
else
  echo "  ❌ Test 8 FAILED — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 9 — DELETE /devices/token (already deleted) → 200 deleted=false
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 9 — DELETE /devices/token (not found) → 200 deleted=false ──"

RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"nonexistent-token-xyz\"}")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"

if [[ "$HTTP" == "200" ]]; then
  DELETED=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',d).get('deleted', 'MISSING'))" 2>/dev/null || echo "MISSING")
  if [[ "$DELETED" == "False" ]]; then
    echo "  ✅ Test 9 — 200 OK, deleted=false (token not found)"
  else
    echo "  ⚠️  Test 9 — 200 OK, deleted=$DELETED"
  fi
else
  echo "  ❌ Test 9 FAILED — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# TEST 10 — Multi-platform: register IOS token
# ─────────────────────────────────────────────────────────
echo ""
echo "── Test 10 — POST /devices/token (IOS platform) → 200 ──"

RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/devices/token" \
  -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d "{
    \"token\":\"ios-apns-token-$(date +%s)\",
    \"platform\":\"IOS\",
    \"deviceName\":\"iPhone 15 Pro\"
  }")
HTTP=$(echo "$RESP" | tail -1)

echo "  HTTP $HTTP"

if [[ "$HTTP" == "200" ]]; then
  echo "  ✅ Test 10 — 200 OK, IOS platform accepted"
else
  echo "  ❌ Test 10 FAILED — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────
# SUMMARY
# ─────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════"
echo "  Story 8.0 — All curl tests completed."
echo "═══════════════════════════════════════════════════"
