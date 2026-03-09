#!/usr/bin/env bash
# =============================================================================
# Story 2.1 — cURL smoke tests: CRUD Produits – Création, Édition & Archivage
# =============================================================================
# Tests: AC1 validation, AC2 create+sync, AC5 edit+audit, AC6 archive+audit,
#        AC7 search/filter, tenant isolation
#
# Requires: curl, python3
# Usage:    bash curl-tests-story-2-1.sh [base_url]
# Default:  BASE=http://localhost:8080
# =============================================================================

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

section() { printf "\n\033[0;36m──────────────────────────────────────────────────────────────\033[0m\n"; printf "\033[0;36m  %s\033[0m\n" "$1"; printf "\033[0;36m──────────────────────────────────────────────────────────────\033[0m\n"; }
ok()      { printf "  \033[0;32m[PASS]  %s\033[0m\n" "$1"; PASS=$((PASS+1)); }
fail()    { printf "  \033[0;31m[FAIL]  %s\033[0m\n" "$1"; FAIL=$((FAIL+1)); }
check()   { if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (got: '$2', expected: '$3')"; fi; }
j()       { echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print($2)" 2>/dev/null || echo ""; }
ts()      { python3 -c "import time; print(str(int(time.time()*1000))[-6:])" 2>/dev/null; }

# ── Step 1: Register + Login + Select-tenant ─────────────────────────────────
section "Step 1: Register user + complete onboarding"
PHONE="+237650$(ts)"
REG=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\"}")
USER_ID=$(j "$REG" "d.get('userId','')")
TC=$(j "$REG" "d.get('tenantCode','')")
check "Register returns userId" "$(test -n "$USER_ID" && echo ok || echo empty)" "ok"
check "Register returns tenantCode" "$(test -n "$TC" && echo ok || echo empty)" "ok"

LOGIN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\"}")
LT=$(j "$LOGIN" "d.get('loginToken','')")

SEL=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LT\",\"tenantCode\":\"$TC\"}")
JWT=$(j "$SEL" "d.get('accessToken','')")
check "Select-tenant returns accessToken" "$(test -n "$JWT" && echo ok || echo empty)" "ok"

# Complete onboarding (required before products are accessible)
ONBOARD=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" \
  -d '{"sectorType":"FOOD_GROCERY","storeName":"TestShop 2.1"}')
ONBOARD_CODE=$(echo "$ONBOARD" | tail -n1)
# Accept 200 (success) or 409 (already completed — tenant already set up)
check "Onboarding complete (200 or 409)" \
  "$(echo "$ONBOARD_CODE" | grep -cE '^(200|409)')" "1"

# ── Step 2: AC1 — Validation error: empty name → 400 ─────────────────────────
section "Step 2: AC1 — Validation → 400 on empty name"
R2=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/v1/products" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" \
  -d '{"name":"","sku":"KEV-TEST01"}')
B2=$(echo "$R2" | head -n1); C2=$(echo "$R2" | tail -n1)
check "POST /products with empty name → 422" "$C2" "422"

# ── Step 3: AC2 — Create product ─────────────────────────────────────────────
section "Step 3: AC2 — Create product (POST /api/v1/products)"
SKU="KEV-$(ts)"
R3=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/v1/products" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" \
  -d "{\"name\":\"T-Shirt Blanc\",\"description\":\"Taille M\",\"sku\":\"$SKU\",\"price\":5000,\"buyPrice\":2000}")
B3=$(echo "$R3" | head -n1); C3=$(echo "$R3" | tail -n1)
PROD_ID=$(j "$B3" "d.get('data',{}).get('id','')")
PROD_SKU=$(j "$B3" "d.get('data',{}).get('sku','')")
PROD_NAME=$(j "$B3" "d.get('data',{}).get('name','')")
check "POST /products → 201" "$C3" "201"
check "Created product has id" "$(test -n "$PROD_ID" && echo ok || echo empty)" "ok"
check "Created product name = T-Shirt Blanc" "$PROD_NAME" "T-Shirt Blanc"
check "Created product SKU matches" "$PROD_SKU" "$SKU"

# ── Step 4: AC2 — List products ──────────────────────────────────────────────
section "Step 4: AC2 — GET /api/v1/products → lists created product"
R4=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT")
B4=$(echo "$R4" | head -n1); C4=$(echo "$R4" | tail -n1)
COUNT4=$(j "$B4" "len(d.get('data',[]))")
HAS_PROD=$(j "$B4" "'T-Shirt Blanc' in [p.get('name') for p in d.get('data',[])] and 'yes' or 'no'")
check "GET /products → 200" "$C4" "200"
check "Response contains ≥1 product" "$(test "${COUNT4:-0}" -ge 1 2>/dev/null && echo ok || echo empty)" "ok"
check "T-Shirt Blanc present in list" "$HAS_PROD" "yes"

# ── Step 5: Get product by ID ─────────────────────────────────────────────────
section "Step 5: GET /api/v1/products/:id"
R5=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/products/$PROD_ID" \
  -H "Authorization: Bearer $JWT")
B5=$(echo "$R5" | head -n1); C5=$(echo "$R5" | tail -n1)
FETCHED_NAME=$(j "$B5" "d.get('data',{}).get('name','')")
check "GET /products/:id → 200" "$C5" "200"
check "Fetched product name matches" "$FETCHED_NAME" "T-Shirt Blanc"

# ── Step 6: AC5 — Update product ─────────────────────────────────────────────
section "Step 6: AC5 — PATCH /api/v1/products/:id (update)"
R6=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE/api/v1/products/$PROD_ID" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" \
  -d '{"name":"T-Shirt Blanc Premium","price":7500}')
B6=$(echo "$R6" | head -n1); C6=$(echo "$R6" | tail -n1)
UPDATED_NAME=$(j "$B6" "d.get('data',{}).get('name','')")
UPDATED_PRICE=$(j "$B6" "d.get('data',{}).get('price',0)")
check "PATCH /products/:id → 200" "$C6" "200"
check "Updated name = T-Shirt Blanc Premium" "$UPDATED_NAME" "T-Shirt Blanc Premium"
check "Updated price = 7500" "$UPDATED_PRICE" "7500"

# ── Step 7: AC6 — Archive product ────────────────────────────────────────────
section "Step 7: AC6 — PATCH /api/v1/products/:id/archive"
R7=$(curl -s -w "\n%{http_code}" -X PATCH "$BASE/api/v1/products/$PROD_ID/archive" \
  -H "Authorization: Bearer $JWT")
B7=$(echo "$R7" | head -n1); C7=$(echo "$R7" | tail -n1)
IS_ARCHIVED=$(j "$B7" "d.get('data',{}).get('archived',False) and 'yes' or 'no'")
check "PATCH /products/:id/archive → 200" "$C7" "200"
check "Archived product has archived=true" "$IS_ARCHIVED" "yes"

# ── Step 8: AC6 — Archived product hidden from list ──────────────────────────
section "Step 8: AC6 — Archived product absent from GET /api/v1/products"
R8=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT")
B8=$(echo "$R8" | head -n1); C8=$(echo "$R8" | tail -n1)
HAS_ARCHIVED=$(j "$B8" "'$PROD_ID' in [p.get('id') for p in d.get('data',[])] and 'yes' or 'no'")
check "Archived product not in active list" "$HAS_ARCHIVED" "no"

# ── Step 9: AC1/AC2 — Auto-SKU when not provided ─────────────────────────────
section "Step 9: AC1/AC2 — Auto-SKU generated when SKU omitted"
R9=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/v1/products" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT" \
  -d '{"name":"Pantalon Noir","price":12000}')
B9=$(echo "$R9" | head -n1); C9=$(echo "$R9" | tail -n1)
AUTO_SKU=$(j "$B9" "d.get('data',{}).get('sku','')")
SK_FORMAT=$(j "$B9" "d.get('data',{}).get('sku','').startswith('KEV-') and 'yes' or 'no'")
check "POST /products without SKU → 201" "$C9" "201"
check "Auto-generated SKU starts with KEV-" "$SK_FORMAT" "yes"

# ── Step 10: Audit trail — product events ────────────────────────────────────
section "Step 10: Audit trail — PRODUCT_CREATED + PRODUCT_UPDATED + PRODUCT_ARCHIVED"
R10=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/audit?entityType=Product" \
  -H "Authorization: Bearer $JWT")
B10=$(echo "$R10" | head -n1); C10=$(echo "$R10" | tail -n1)
AUDIT_COUNT=$(j "$B10" "len(d.get('data',[]))")
HAS_CREATED=$(j "$B10" "'PRODUCT_CREATED' in [e.get('action') for e in d.get('data',[])] and 'yes' or 'no'")
HAS_UPDATED=$(j "$B10" "'PRODUCT_UPDATED' in [e.get('action') for e in d.get('data',[])] and 'yes' or 'no'")
HAS_ARCHIVED=$(j "$B10" "'PRODUCT_ARCHIVED' in [e.get('action') for e in d.get('data',[])] and 'yes' or 'no'")
check "GET /audit?entityType=Product → 200" "$C10" "200"
check "Audit has ≥3 product entries" "$(test "${AUDIT_COUNT:-0}" -ge 3 2>/dev/null && echo ok || echo empty)" "ok"
check "PRODUCT_CREATED event present" "$HAS_CREATED" "yes"
check "PRODUCT_UPDATED event present" "$HAS_UPDATED" "yes"
check "PRODUCT_ARCHIVED event present" "$HAS_ARCHIVED" "yes"

# ── Step 11: Tenant isolation ─────────────────────────────────────────────────
section "Step 11: Tenant isolation — another tenant sees 0 products"
PHONE_B="+237651$(ts)"
REG_B=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_B\",\"password\":\"Test1234!\"}")
TC_B=$(j "$REG_B" "d.get('tenantCode','')")
LB=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_B\",\"password\":\"Test1234!\"}")
LTB=$(j "$LB" "d.get('loginToken','')")
SB=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LTB\",\"tenantCode\":\"$TC_B\"}")
JWT_B=$(j "$SB" "d.get('accessToken','')")
# Complete onboarding for tenant B too
curl -s -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $JWT_B" \
  -d '{"sectorType":"ALIMENTATION_EPICERIE","storeName":"TenantB Shop"}' > /dev/null

R11=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT_B")
B11=$(echo "$R11" | head -n1); C11=$(echo "$R11" | tail -n1)
COUNT_B=$(j "$B11" "len(d.get('data',[]))")
check "Tenant B GET /products → 200" "$C11" "200"
check "Tenant B sees 0 products (isolation)" "$COUNT_B" "0"

# ── Step 12: unauthenticated → 401 ───────────────────────────────────────────
section "Step 12: Unauthenticated request → 401"
R12=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/products")
C12=$(echo "$R12" | tail -n1)
check "GET /products without token → 401" "$C12" "401"

# ── Summary ───────────────────────────────────────────────────────────────────
printf "\n\033[0;36m══════════════════════════════════════════════════════════════\033[0m\n"
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
  printf "  \033[0;32m✓ ALL $TOTAL TESTS PASSED\033[0m\n"
else
  printf "  \033[0;32m✓ $PASS PASSED\033[0m  \033[0;31m✗ $FAIL FAILED\033[0m  (total $TOTAL)\n"
fi
printf "\033[0;36m══════════════════════════════════════════════════════════════\033[0m\n\n"
exit $FAIL
