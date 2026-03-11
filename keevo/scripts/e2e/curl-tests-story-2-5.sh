#!/usr/bin/env bash
# =============================================================================
# curl-tests-story-2-5.sh — E2E verification for Story 2.5
# Gestion Clients & Fournisseurs
#
# Self-contained: each run registers a fresh user (phone-based), completes
# two-step login, creates clients and suppliers, and exercises the full
# contact API including AC5 (GET /products/{id}/supplier).
#
# Prerequisites:
#   - Backend running on http://localhost:8080
#   - python3 available
#
# Usage:
#   chmod +x scripts/e2e/curl-tests-story-2-5.sh
#   ./scripts/e2e/curl-tests-story-2-5.sh
# =============================================================================

BASE_URL="http://localhost:8080"
GREEN="\033[0;32m"
RED="\033[0;31m"
CYAN="\033[0;36m"
NC="\033[0m"
PASS="${GREEN}✓${NC}"
FAIL="${RED}✗${NC}"
INFO="${CYAN}ℹ${NC}"

fail() { echo -e "${FAIL} FAILED: $1"; exit 1; }
ok()   { echo -e "${PASS} $1"; }
info() { echo -e "${INFO} $1"; }

# JSON extractor using python3 (no jq required)
j() { echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print($2)" 2>/dev/null || echo ""; }
ts() { python3 -c "import time; print(str(int(time.time()*1000))[-8:])" 2>/dev/null; }

SUFFIX=$(ts)
PHONE="+237622${SUFFIX: -6}"
PASSWORD="Test@1234${SUFFIX}"
SKU="KEV-${SUFFIX: -6}"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Story 2.5 — Gestion Clients & Fournisseurs E2E         ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 1 — Register user (phone-based, auto-provisions tenant schema)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 1/16 — Register user ${PHONE}"
REGISTER=$(curl -s -X POST "${BASE_URL}/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"${PHONE}\",\"password\":\"${PASSWORD}\"}")
TENANT_CODE=$(j "$REGISTER" "d.get('tenantCode','')")
[[ -n "$TENANT_CODE" ]] || fail "Registration failed: $REGISTER"
ok "User registered — tenantCode=${TENANT_CODE}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 2 — Login → loginToken
# ─────────────────────────────────────────────────────────────────────────────
info "Step 2/16 — Login"
LOGIN=$(curl -s -X POST "${BASE_URL}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"${PHONE}\",\"password\":\"${PASSWORD}\"}")
LOGIN_TOKEN=$(j "$LOGIN" "d.get('loginToken','')")
[[ -n "$LOGIN_TOKEN" ]] || fail "Login failed: $LOGIN"
ok "Login successful — loginToken obtained"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 3 — Select tenant → accessToken
# ─────────────────────────────────────────────────────────────────────────────
info "Step 3/16 — Select tenant"
SELECT=$(curl -s -X POST "${BASE_URL}/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"${LOGIN_TOKEN}\",\"tenantCode\":\"${TENANT_CODE}\"}")
TOKEN=$(j "$SELECT" "d.get('accessToken','')")
[[ -n "$TOKEN" ]] || fail "Select-tenant failed: $SELECT"
ok "Tenant selected — accessToken obtained"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 4 — Create category + product (required for supplier–product link)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 4/16 — Create category + product (SKU: ${SKU})"
CAT=$(curl -s -X POST "${BASE_URL}/api/v1/categories" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"TestCat${SUFFIX}\"}")
CAT_ID=$(j "$CAT" "d.get('data',{}).get('id','')")
[[ -n "$CAT_ID" ]] || fail "Category creation failed: $CAT"

PRODUCT=$(curl -s -X POST "${BASE_URL}/api/v1/products" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"TestProduct${SUFFIX}\",\"sku\":\"${SKU}\",\"categoryId\":\"${CAT_ID}\",\"price\":5000,\"buyPrice\":3000,\"stockQuantity\":10}")
PRODUCT_ID=$(j "$PRODUCT" "d.get('data',{}).get('id','')")
[[ -n "$PRODUCT_ID" ]] || fail "Product creation failed: $PRODUCT"
ok "Product created: ${PRODUCT_ID}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 5 — Create a client
# ─────────────────────────────────────────────────────────────────────────────
info "Step 5/16 — Create client"
CREATE_CLIENT=$(curl -s -X POST "${BASE_URL}/api/v1/clients" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Alice Durand\",\"phone\":\"+22670000001\",\"email\":\"alice@keevo.io\",\"notes\":\"VIP client\"}")
CLIENT_ID=$(j "$CREATE_CLIENT" "d.get('data',{}).get('id','')")
CLIENT_NAME=$(j "$CREATE_CLIENT" "d.get('data',{}).get('name','')")
[[ -n "$CLIENT_ID" ]] || fail "Client creation failed: $CREATE_CLIENT"
[[ "$CLIENT_NAME" == "Alice Durand" ]] || fail "Expected name=Alice Durand, got: $CLIENT_NAME"
ok "Client created: ${CLIENT_ID} — ${CLIENT_NAME}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 6 — List clients
# ─────────────────────────────────────────────────────────────────────────────
info "Step 6/16 — List clients"
CLIENTS=$(curl -s -X GET "${BASE_URL}/api/v1/clients" \
  -H "Authorization: Bearer ${TOKEN}")
LIST_COUNT=$(j "$CLIENTS" "len(d.get('data',[]))")
[[ "$LIST_COUNT" -ge 1 ]] 2>/dev/null || fail "Expected ≥1 client, got: $CLIENTS"
ok "Client list returned ${LIST_COUNT} client(s)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 7 — Get client profile (verify purchaseCount — BUG #1 fix)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 7/16 — Get client profile"
PROFILE=$(curl -s -X GET "${BASE_URL}/api/v1/clients/${CLIENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
PROF_NAME=$(j "$PROFILE" "d.get('data',{}).get('name','')")
PROF_PURCHASE=$(j "$PROFILE" "str(d.get('data',{}).get('purchaseCount','MISSING'))")
[[ "$PROF_NAME" == "Alice Durand" ]] || fail "Expected name=Alice Durand, got: $PROF_NAME"
[[ "$PROF_PURCHASE" != "MISSING" ]] || fail "Expected purchaseCount field, got: $PROFILE"
ok "Client profile: ${PROF_NAME} — purchaseCount=${PROF_PURCHASE}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 8 — Update client
# ─────────────────────────────────────────────────────────────────────────────
info "Step 8/16 — Update client"
UPDATE_CLIENT=$(curl -s -X PATCH "${BASE_URL}/api/v1/clients/${CLIENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Alice Durand Updated\"}")
UPDATED_NAME=$(j "$UPDATE_CLIENT" "d.get('data',{}).get('name','')")
[[ "$UPDATED_NAME" == "Alice Durand Updated" ]] || fail "Expected updated name, got: $UPDATED_NAME"
ok "Client updated: ${UPDATED_NAME}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 9 — Create a supplier (linked to product)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 9/16 — Create supplier"
CREATE_SUPPLIER=$(curl -s -X POST "${BASE_URL}/api/v1/suppliers" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Acme Distribution\",\"phone\":\"+22670000010\",\"email\":\"acme@supplier.io\",\"productIds\":[\"${PRODUCT_ID}\"]}")
SUPPLIER_ID=$(j "$CREATE_SUPPLIER" "d.get('data',{}).get('id','')")
SUPPLIER_NAME=$(j "$CREATE_SUPPLIER" "d.get('data',{}).get('name','')")
[[ -n "$SUPPLIER_ID" ]] || fail "Supplier creation failed: $CREATE_SUPPLIER"
[[ "$SUPPLIER_NAME" == "Acme Distribution" ]] || fail "Expected name=Acme Distribution, got: $SUPPLIER_NAME"
ok "Supplier created: ${SUPPLIER_ID} — ${SUPPLIER_NAME}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 10 — List suppliers
# ─────────────────────────────────────────────────────────────────────────────
info "Step 10/16 — List suppliers"
SUPPLIERS=$(curl -s -X GET "${BASE_URL}/api/v1/suppliers" \
  -H "Authorization: Bearer ${TOKEN}")
SUP_COUNT=$(j "$SUPPLIERS" "len(d.get('data',[]))")
[[ "$SUP_COUNT" -ge 1 ]] 2>/dev/null || fail "Expected ≥1 supplier, got: $SUPPLIERS"
ok "Supplier list returned ${SUP_COUNT} supplier(s)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 11 — Get supplier profile (verify product link)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 11/16 — Get supplier profile (verify product link)"
SUP_PROFILE=$(curl -s -X GET "${BASE_URL}/api/v1/suppliers/${SUPPLIER_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
SUP_PROF_NAME=$(j "$SUP_PROFILE" "d.get('data',{}).get('name','')")
PRODUCT_IDS_COUNT=$(j "$SUP_PROFILE" "len(d.get('data',{}).get('productIds',[]))")
[[ "$SUP_PROF_NAME" == "Acme Distribution" ]] || fail "Expected name=Acme Distribution, got: $SUP_PROF_NAME"
[[ "$PRODUCT_IDS_COUNT" -ge 1 ]] 2>/dev/null || fail "Expected ≥1 productId, got: $SUP_PROFILE"
ok "Supplier profile: ${SUP_PROF_NAME} — ${PRODUCT_IDS_COUNT} product(s) linked"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 12 — Get product's supplier (AC5 — GET /products/{id}/supplier)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 12/16 — Get supplier by product ID (AC5)"
PROD_SUPPLIER=$(curl -s -X GET "${BASE_URL}/api/v1/products/${PRODUCT_ID}/supplier" \
  -H "Authorization: Bearer ${TOKEN}")
PROD_SUP_NAME=$(j "$PROD_SUPPLIER" "d.get('data',{}).get('name','')")
[[ "$PROD_SUP_NAME" == "Acme Distribution" ]] || fail "Expected supplier Acme Distribution for product, got: $PROD_SUPPLIER"
ok "Product supplier resolved: ${PROD_SUP_NAME} (AC5 ✓)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 13 — Update supplier
# ─────────────────────────────────────────────────────────────────────────────
info "Step 13/16 — Update supplier"
UPDATE_SUPPLIER=$(curl -s -X PATCH "${BASE_URL}/api/v1/suppliers/${SUPPLIER_ID}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"Acme Distribution Updated\"}")
UPDATED_SUP_NAME=$(j "$UPDATE_SUPPLIER" "d.get('data',{}).get('name','')")
[[ "$UPDATED_SUP_NAME" == "Acme Distribution Updated" ]] || fail "Expected updated name, got: $UPDATED_SUP_NAME"
ok "Supplier updated: ${UPDATED_SUP_NAME}"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 14 — Archive client (soft-delete → HTTP 204)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 14/16 — Archive client"
ARCHIVE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  "${BASE_URL}/api/v1/clients/${CLIENT_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
[[ "$ARCHIVE_STATUS" == "204" ]] || fail "Expected 204 for archive, got: ${ARCHIVE_STATUS}"
ok "Client archived (HTTP 204)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 15 — Archive supplier (soft-delete → HTTP 204)
# ─────────────────────────────────────────────────────────────────────────────
info "Step 15/16 — Archive supplier"
ARCHIVE_SUP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE \
  "${BASE_URL}/api/v1/suppliers/${SUPPLIER_ID}" \
  -H "Authorization: Bearer ${TOKEN}")
[[ "$ARCHIVE_SUP_STATUS" == "204" ]] || fail "Expected 204 for archive, got: ${ARCHIVE_SUP_STATUS}"
ok "Supplier archived (HTTP 204)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 16 — Verify archived entities visible with includeArchived=true
# ─────────────────────────────────────────────────────────────────────────────
info "Step 16/16 — Verify archived entities visible with includeArchived=true"
CLIENTS_ARCHIVED=$(curl -s -X GET "${BASE_URL}/api/v1/clients?includeArchived=true" \
  -H "Authorization: Bearer ${TOKEN}")
ARCHIVED_COUNT=$(j "$CLIENTS_ARCHIVED" "sum(1 for c in d.get('data',[]) if c.get('archived'))")
[[ "$ARCHIVED_COUNT" -ge 1 ]] 2>/dev/null || fail "Expected ≥1 archived client, got: $CLIENTS_ARCHIVED"
ok "Archived clients visible: ${ARCHIVED_COUNT} archived"

SUPPLIERS_ARCHIVED=$(curl -s -X GET "${BASE_URL}/api/v1/suppliers?includeArchived=true" \
  -H "Authorization: Bearer ${TOKEN}")
ARCHIVED_SUP_COUNT=$(j "$SUPPLIERS_ARCHIVED" "sum(1 for s in d.get('data',[]) if s.get('archived'))")
[[ "$ARCHIVED_SUP_COUNT" -ge 1 ]] 2>/dev/null || fail "Expected ≥1 archived supplier, got: $SUPPLIERS_ARCHIVED"
ok "Archived suppliers visible: ${ARCHIVED_SUP_COUNT} archived"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Story 2.5 — ALL 16 STEPS PASSED ✅                     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
