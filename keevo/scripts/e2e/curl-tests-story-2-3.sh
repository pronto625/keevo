#!/usr/bin/env bash
# =============================================================================
# curl-tests-story-2-3.sh — E2E verification for Story 2.3
# Stock Thresholds & Movement History
#
# Self-contained: each run registers a fresh user, selects a tenant,
# creates a product, and exercises the full stock API.
#
# Prerequisites:
#   - Backend running on http://localhost:8080
#   - jq installed (brew install jq / apt install jq)
#
# Usage:
#   chmod +x scripts/e2e/curl-tests-story-2-3.sh
#   ./scripts/e2e/curl-tests-story-2-3.sh
# =============================================================================

set -euo pipefail

BASE_URL="http://localhost:8080"
PASS="\033[0;32m✓\033[0m"
FAIL="\033[0;31m✗\033[0m"
INFO="\033[0;36mℹ\033[0m"

fail() { echo -e "${FAIL} FAILED: $1"; exit 1; }
ok()   { echo -e "${PASS} $1"; }
info() { echo -e "${INFO} $1"; }

# ── Generate unique identifiers for this run ─────────────────────────────────
SUFFIX=$(date +%s)
EMAIL="stocktest+${SUFFIX}@keevo.io"
PASSWORD="Test@1234${SUFFIX}"
SHOP_NAME="StockTestShop${SUFFIX}"
# KEV-[A-Z0-9]{6} — 6 random uppercase alphanumerics
SKU="KEV-$(cat /dev/urandom | tr -dc 'A-Z' | head -c 6)"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Story 2.3 — Stock Thresholds & Movement History E2E    ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 1 — Register user
# ─────────────────────────────────────────────────────────────────────────────
info "Step 1/13 — Register user ${EMAIL}"
REGISTER=$(curl -s -X POST "${BASE_URL}/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\",\"firstName\":\"Stock\",\"lastName\":\"Tester\"}")
echo "$REGISTER" | jq -e '.success == true' > /dev/null || fail "Registration failed: $REGISTER"
ok "User registered"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 2 — Login
# ─────────────────────────────────────────────────────────────────────────────
info "Step 2/13 — Login"
LOGIN=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${EMAIL}\",\"password\":\"${PASSWORD}\"}")
TOKEN=$(echo "$LOGIN" | jq -r '.data.accessToken')
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "Login failed: $LOGIN"
ok "Login successful — token obtained"

AUTH="-H \"Authorization: Bearer ${TOKEN}\""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 3 — Complete onboarding (tenant provisioning)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 3/13 — Complete onboarding"
ONBOARD=$(curl -s -X POST "${BASE_URL}/api/v1/onboarding/complete" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"shopName\":\"${SHOP_NAME}\",\"sector\":\"RETAIL\",\"acceptedTerms\":true,\"phoneNumber\":\"+33600000001\"}")
echo "$ONBOARD" | jq -e '.success == true' > /dev/null || fail "Onboarding failed: $ONBOARD"
ok "Onboarding complete"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 4 — Select tenant
# ─────────────────────────────────────────────────────────────────────────────
info "Step 4/13 — Select tenant"
TENANT_LIST=$(curl -s -X GET "${BASE_URL}/api/v1/auth/tenants" \
  -H "Authorization: Bearer ${TOKEN}")
TENANT_ID=$(echo "$TENANT_LIST" | jq -r '.data[0].tenantId')
[[ -n "$TENANT_ID" && "$TENANT_ID" != "null" ]] || fail "No tenant found: $TENANT_LIST"

SELECT=$(curl -s -X POST "${BASE_URL}/api/v1/auth/select-tenant" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"${TENANT_ID}\"}")
TOKEN=$(echo "$SELECT" | jq -r '.data.accessToken')
[[ -n "$TOKEN" && "$TOKEN" != "null" ]] || fail "Select-tenant failed: $SELECT"
ok "Tenant selected — new token obtained"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 5 — Create a category
# ─────────────────────────────────────────────────────────────────────────────
info "Step 5/13 — Create category"
CAT=$(curl -s -X POST "${BASE_URL}/api/v1/categories" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"TestCat${SUFFIX}\"}")
CAT_ID=$(echo "$CAT" | jq -r '.data.id')
[[ -n "$CAT_ID" && "$CAT_ID" != "null" ]] || fail "Category creation failed: $CAT"
ok "Category created: ${CAT_ID}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 6 — Create a product
# ─────────────────────────────────────────────────────────────────────────────
info "Step 6/13 — Create product (SKU: ${SKU})"
PRODUCT=$(curl -s -X POST "${BASE_URL}/api/v1/products" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"StockTestProduct\",\"description\":\"test\",\"sku\":\"${SKU}\",\"categoryId\":\"${CAT_ID}\",\"price\":5000,\"buyPrice\":3000,\"transportCost\":200,\"stockQuantity\":0}")
PRODUCT_ID=$(echo "$PRODUCT" | jq -r '.data.id')
[[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] || fail "Product creation failed: $PRODUCT"
ok "Product created: ${PRODUCT_ID}"

STORE_ID=$(uuidgen || python3 -c "import uuid; print(uuid.uuid4())")

# ─────────────────────────────────────────────────────────────────────────────
# STEP 7 — Set minimum threshold
# ─────────────────────────────────────────────────────────────────────────────
info "Step 7/13 — Set minimum threshold to 5"
THRESHOLD=$(curl -s -X PATCH "${BASE_URL}/api/v1/products/${PRODUCT_ID}/threshold" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"minimumThreshold\":5}")
THRESH_VAL=$(echo "$THRESHOLD" | jq -r '.data.minimumThreshold')
[[ "$THRESH_VAL" == "5" ]] || fail "Expected threshold=5, got: $THRESHOLD"
ok "Threshold set to 5"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 8 — Stock entry: receive 20 units
# ─────────────────────────────────────────────────────────────────────────────
info "Step 8/13 — Stock entry: +20 units"
ENTRY=$(curl -s -X POST "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock/entry" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"${STORE_ID}\",\"quantity\":20,\"notes\":\"Initial delivery BL-001\"}")
QTY_AFTER=$(echo "$ENTRY" | jq -r '.data.quantityAfter')
[[ "$QTY_AFTER" == "20" ]] || fail "Expected quantityAfter=20, got: $ENTRY"
ok "Stock entry recorded — quantity now 20"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 9 — Get current stock (verify level + isLow=false)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 9/13 — Get current stock"
STOCK=$(curl -s "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock" \
  -H "Authorization: Bearer ${TOKEN}")
LEVEL_QTY=$(echo "$STOCK" | jq -r '.data[0].quantity')
IS_LOW=$(echo "$STOCK" | jq -r '.data[0].isLow')
[[ "$LEVEL_QTY" == "20" ]] || fail "Expected quantity=20, got: $STOCK"
[[ "$IS_LOW" == "false" ]] || fail "Expected isLow=false at qty=20 (threshold=5), got: $STOCK"
ok "Stock level: 20 units — isLow=false ✓"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 10 — Adjust stock to 4 (below threshold)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 10/13 — Adjust stock to 4 (below threshold of 5)"
ADJUST=$(curl -s -X POST "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock/adjust" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"${STORE_ID}\",\"newQuantity\":4,\"notes\":\"Physical count — discrepancy found\"}")
QTY_ADJ=$(echo "$ADJUST" | jq -r '.data.quantityAfter')
[[ "$QTY_ADJ" == "4" ]] || fail "Expected quantityAfter=4, got: $ADJUST"
ok "Adjust recorded — quantity now 4 (threshold breach triggered)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 11 — Get current stock (verify isLow=true)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 11/13 — Verify isLow=true after threshold breach"
STOCK2=$(curl -s "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock" \
  -H "Authorization: Bearer ${TOKEN}")
IS_LOW2=$(echo "$STOCK2" | jq -r '.data[0].isLow')
THRESH_CHK=$(echo "$STOCK2" | jq -r '.data[0].minimumThreshold')
[[ "$IS_LOW2" == "true" ]] || fail "Expected isLow=true at qty=4 (threshold=5), got: $STOCK2"
[[ "$THRESH_CHK" == "5" ]] || fail "Expected minimumThreshold=5, got: $STOCK2"
ok "isLow=true confirmed (qty=4 ≤ threshold=5)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 12 — Get movement history (verify 2 movements)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 12/13 — Get movement history (expect ≥ 2 movements)"
HISTORY=$(curl -s "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock/history?page=0&size=20" \
  -H "Authorization: Bearer ${TOKEN}")
TOTAL=$(echo "$HISTORY" | jq -r '.data.totalElements')
[[ "$TOTAL" -ge 2 ]] 2>/dev/null || fail "Expected ≥ 2 movements, got totalElements=${TOTAL}: $HISTORY"
FIRST_TYPE=$(echo "$HISTORY" | jq -r '.data.content[0].movementType')
ok "History: ${TOTAL} movements found — most recent type: ${FIRST_TYPE}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 13 — Reject insufficient stock
# ─────────────────────────────────────────────────────────────────────────────
info "Step 13/13 — Guard: reject entry with negative result (INSUFFICIENT_STOCK)"
BAD=$(curl -s -X POST "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock/adjust" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"${STORE_ID}\",\"newQuantity\":-1,\"notes\":\"invalid\"}")
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
  "${BASE_URL}/api/v1/products/${PRODUCT_ID}/stock/adjust" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"${STORE_ID}\",\"newQuantity\":-1,\"notes\":\"invalid\"}")
[[ "$HTTP_CODE" == "400" || "$HTTP_CODE" == "422" ]] || fail "Expected 400/422, got HTTP ${HTTP_CODE}"
ok "Invalid adjust rejected with HTTP ${HTTP_CODE}"

# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║         Story 2.3 E2E — ALL 13 STEPS PASSED ✓           ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
