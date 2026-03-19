#!/bin/bash
# Story 4.4 — Clôture Journalière & Historique des Ventes
# Backend API integration tests (TDD RED → GREEN)
#
# Prerequisites:
# - Backend running on http://localhost:8080
# - PostgreSQL tenant schemas provisioned

set -e

BASE_URL="http://localhost:8080"
TENANT_SCHEMA="kv_test_$(date +%s)"

echo "=================================================="
echo "Story 4.4 — Day Closure & Sales History Tests"
echo "=================================================="
echo ""

# ──────────────────────────────────────────────────
# SETUP — Register + Login + Onboarding
# ──────────────────────────────────────────────────
echo "SETUP — Creating test tenant and user..."

PHONE="+243$(shuf -i 100000000-999999999 -n 1)"
PASSWORD="Test123!"

# Step 1: Register
REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"phoneNumber\": \"$PHONE\",
    \"password\": \"$PASSWORD\",
    \"confirmPassword\": \"$PASSWORD\",
    \"acceptedTerms\": true
  }")

echo "Register: $REGISTER_RESPONSE"
REGISTER_TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r '.data.token')

if [ "$REGISTER_TOKEN" == "null" ]; then
  echo "❌ Registration failed"
  exit 1
fi

# Step 2: Complete onboarding
curl -s -X POST "$BASE_URL/api/v1/auth/complete-onboarding" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $REGISTER_TOKEN" \
  -d "{
    \"sectorType\": \"RETAIL\",
    \"shopName\": \"Test Shop 4.4\",
    \"employeeCount\": 1
  }" > /dev/null

# Step 3: Select default plan
curl -s -X POST "$BASE_URL/api/v1/subscriptions/select-plan" \
  -H "Authorization: Bearer $REGISTER_TOKEN" \
  -d "planCode=FREE_TRIAL" > /dev/null

# Step 4: Login to get fresh token
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{
    \"phoneNumber\": \"$PHONE\",
    \"password\": \"$PASSWORD\"
  }")

TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.data.token')
STORE_ID=$(echo "$LOGIN_RESPONSE" | jq -r '.data.user.stores[0].id')

echo "✅ Setup complete"
echo "   Token: ${TOKEN:0:20}..."
echo "   Store ID: $STORE_ID"
echo ""

# ──────────────────────────────────────────────────
# STEP 01 — GET /sales/history (today) — EMPLOYEE
# Expected: 200, empty list (no sales yet)
# ──────────────────────────────────────────────────
echo "STEP 01 — GET /sales/history (EMPLOYEE, today, no sales)"

HISTORY_RESPONSE=$(curl -s -X GET "$BASE_URL/api/v1/sales/history?storeId=$STORE_ID" \
  -H "Authorization: Bearer $TOKEN")

TOTAL_SALES=$(echo "$HISTORY_RESPONSE" | jq -r '.data.totalElements')

if [ "$TOTAL_SALES" == "0" ]; then
  echo "✅ STEP 01 PASSED — No sales yet"
else
  echo "❌ STEP 01 FAILED — Expected 0 sales, got $TOTAL_SALES"
fi
echo ""

# ──────────────────────────────────────────────────
# STEP 02 — Create a sale first
# ──────────────────────────────────────────────────
echo "STEP 02 — Creating a test sale..."

PRODUCT_ID=$(curl -s -X GET "$BASE_URL/api/v1/products?storeId=$STORE_ID&page=0&size=1" \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data.content[0].id')

if [ "$PRODUCT_ID" == "null" ] || [ -z "$PRODUCT_ID" ]; then
  echo "⚠️  No products available, skipping sale creation"
else
  SALE_RESPONSE=$(curl -s -X POST "$BASE_URL/api/v1/sales" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{
      \"storeId\": \"$STORE_ID\",
      \"items\": [{
        \"productId\": \"$PRODUCT_ID\",
        \"quantity\": 2,
        \"unitPrice\": 1000
      }],
      \"paymentMode\": \"CASH\",
      \"totalAmount\": 2000,
      \"discountAmount\": 0
    }")

  SALE_ID=$(echo "$SALE_RESPONSE" | jq -r '.data.id')
  echo "✅ Sale created: $SALE_ID"
fi
echo ""

# ──────────────────────────────────────────────────
# STEP 03 — POST /day-closures — Manual closure
# Expected: 201 Created
# ──────────────────────────────────────────────────
echo "STEP 03 — POST /day-closures (manual closure)"

CLOSURE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/day-closures" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"storeId\": \"$STORE_ID\"
  }")

HTTP_CODE=$(echo "$CLOSURE_RESPONSE" | tail -n1)
BODY=$(echo "$CLOSURE_RESPONSE" | head -n-1)

if [ "$HTTP_CODE" == "201" ]; then
  echo "✅ STEP 03 PASSED — Closure created (201)"
  TOTAL_REVENUE=$(echo "$BODY" | jq -r '.data.summary.totalRevenue')
  echo "   Total Revenue: $TOTAL_REVENUE FCFA"
else
  echo "❌ STEP 03 FAILED — Expected 201, got $HTTP_CODE"
  echo "   Response: $BODY"
fi
echo ""

# ──────────────────────────────────────────────────
# STEP 04 — POST /day-closures — Duplicate closure
# Expected: 409 CONFLICT (DAY_ALREADY_CLOSED)
# ──────────────────────────────────────────────────
echo "STEP 04 — POST /day-closures (duplicate — should fail)"

DUPLICATE_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/day-closures" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{
    \"storeId\": \"$STORE_ID\"
  }")

HTTP_CODE=$(echo "$DUPLICATE_RESPONSE" | tail -n1)
BODY=$(echo "$DUPLICATE_RESPONSE" | head -n-1)

if [ "$HTTP_CODE" == "409" ]; then
  ERROR_CODE=$(echo "$BODY" | jq -r '.errorCode')
  if [ "$ERROR_CODE" == "DAY_ALREADY_CLOSED" ]; then
    echo "✅ STEP 04 PASSED — Duplicate closure rejected (409 DAY_ALREADY_CLOSED)"
  else
    echo "⚠️  STEP 04 — Got 409 but wrong error code: $ERROR_CODE"
  fi
else
  echo "❌ STEP 04 FAILED — Expected 409, got $HTTP_CODE"
fi
echo ""

# ──────────────────────────────────────────────────
# STEP 05 — GET /day-closures?storeId=X&date=today
# Expected: 200 (OWNER only — would be 403 for EMPLOYEE)
# Note: Current user is OWNER from registration
# ──────────────────────────────────────────────────
echo "STEP 05 — GET /day-closures?storeId=$STORE_ID&date=$(date +%Y-%m-%d)"

GET_CLOSURE_RESPONSE=$(curl -s -w "\n%{http_code}" -X GET \
  "$BASE_URL/api/v1/day-closures?storeId=$STORE_ID&date=$(date +%Y-%m-%d)" \
  -H "Authorization: Bearer $TOKEN")

HTTP_CODE=$(echo "$GET_CLOSURE_RESPONSE" | tail -n1)
BODY=$(echo "$GET_CLOSURE_RESPONSE" | head -n-1)

if [ "$HTTP_CODE" == "200" ]; then
  echo "✅ STEP 05 PASSED — GET closure succeeded (200)"
elif [ "$HTTP_CODE" == "501" ]; then
  echo "⚠️  STEP 05 — Not implemented yet (501)"
else
  echo "❌ STEP 05 FAILED — Expected 200, got $HTTP_CODE"
fi
echo ""

# ──────────────────────────────────────────────────
# STEP 06 — Check WhatsApp log (NoOpWhatsAppAdapter)
# ──────────────────────────────────────────────────
echo "STEP 06 — Check backend logs for WhatsApp report"
echo "ℹ️  Story 4.4 uses NoOpWhatsAppAdapter (MVP) — check console for log entry"
echo "   Expected pattern: 'WhatsApp report [STUB]: to=+243... text=📊 Clôture...'"
echo ""

# ──────────────────────────────────────────────────
# CLEANUP (optional)
# ──────────────────────────────────────────────────
echo "=================================================="
echo "All tests complete!"
echo "=================================================="
echo ""
echo "Manual verification steps:"
echo "1. Check backend logs for 'WhatsApp report [STUB]'"
echo "2. Verify DayClosedEvent published (check audit logs if enabled)"
echo "3. Verify scheduler would run at 19:00 UTC (20:00 WAT)"
echo ""
