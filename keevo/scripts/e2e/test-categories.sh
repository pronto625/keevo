#!/usr/bin/env bash
# =============================================================================
# Category API Tests — Test CRUD complet des catégories
# =============================================================================
# Tests: GET /categories, POST /categories, PATCH /categories/{id}/toggle,
#        GET /categories/roots, GET /categories/{id}/subcategories
#
# Usage: bash test-categories.sh [base_url]
# Default: BASE=http://localhost:8080
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
section "Step 1: Setup authentication"
PHONE="+237650$(ts)"
REG=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\"}")
USER_ID=$(j "$REG" "d.get('userId','')")
TOKEN=$(j "$REG" "d.get('token','')")
check "User registration" "$([ -n \"$USER_ID\" ] && echo True || echo False)" "True"

# Complete onboarding
ONBOARD=$(curl -s -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"sectorType":"CLOTHING","storeName":"Test Store"}')
ONBOARD_SUCCESS=$(j "$ONBOARD" "d.get('success',False)")
check "Onboarding completed" "$ONBOARD_SUCCESS" "True"

# ── Step 2: Get initial categories ───────────────────────────────────────────
section "Step 2: List existing categories from onboarding"
CATEGORIES=$(curl -s -X GET "$BASE/api/v1/categories" \
  -H "Authorization: Bearer $TOKEN")
CATEGORIES_SUCCESS=$(j "$CATEGORIES" "'data' in d")
check "GET /categories returns data" "$CATEGORIES_SUCCESS" "True"

INITIAL_COUNT=$(j "$CATEGORIES" "len(d.get('data',[]))")
ok "Initial categories from onboarding: $INITIAL_COUNT"

# ── Step 3: Get root categories ──────────────────────────────────────────────
section "Step 3: List root categories only"  
ROOTS=$(curl -s -X GET "$BASE/api/v1/categories/roots" \
  -H "Authorization: Bearer $TOKEN")
ROOTS_SUCCESS=$(j "$ROOTS" "'data' in d")
check "GET /categories/roots returns data" "$ROOTS_SUCCESS" "True"

ROOT_COUNT=$(j "$ROOTS" "len(d.get('data',[]))")
ok "Root categories count: $ROOT_COUNT"

# ── Step 4: Create custom root category ──────────────────────────────────────
section "Step 4: Create custom root category"
CREATE_ROOT=$(curl -s -X POST "$BASE/api/v1/categories" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Catégorie Custom","parentId":null}')
CREATE_ROOT_SUCCESS=$(j "$CREATE_ROOT" "'data' in d")
check "POST /categories (root) returns data" "$CREATE_ROOT_SUCCESS" "True"

ROOT_CATEGORY_ID=$(j "$CREATE_ROOT" "d.get('data',{}).get('id','')")
check "Custom category is_custom=true" "$(j "$CREATE_ROOT" "d.get('data',{}).get('isCustom',False)")" "True"
check "Custom category is_active=true" "$(j "$CREATE_ROOT" "d.get('data',{}).get('isActive',False)")" "True"
ok "Created custom root category ID: $ROOT_CATEGORY_ID"

# ── Step 5: Create subcategory ───────────────────────────────────────────────
section "Step 5: Create subcategory under custom root"
CREATE_SUB=$(curl -s -X POST "$BASE/api/v1/categories" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d "{\"name\":\"Sous-catégorie Test\",\"parentId\":\"$ROOT_CATEGORY_ID\"}")
CREATE_SUB_SUCCESS=$(j "$CREATE_SUB" "'data' in d")
check "POST /categories (subcategory) returns data" "$CREATE_SUB_SUCCESS" "True"

SUB_CATEGORY_ID=$(j "$CREATE_SUB" "d.get('data',{}).get('id','')")
check "Subcategory parentId matches" "$(j "$CREATE_SUB" "d.get('data',{}).get('parentId','')")" "$ROOT_CATEGORY_ID"
ok "Created subcategory ID: $SUB_CATEGORY_ID"

# ── Step 6: Get subcategories of root category ───────────────────────────────
section "Step 6: List subcategories"
SUBCATEGORIES=$(curl -s -X GET "$BASE/api/v1/categories/$ROOT_CATEGORY_ID/subcategories" \
  -H "Authorization: Bearer $TOKEN")
SUBCAT_SUCCESS=$(j "$SUBCATEGORIES" "'data' in d")
check "GET /categories/{id}/subcategories returns data" "$SUBCAT_SUCCESS" "True"

SUBCAT_COUNT=$(j "$SUBCATEGORIES" "len(d.get('data',[]))")
check "Subcategory count is 1" "$SUBCAT_COUNT" "1"
ok "Found subcategories: $SUBCAT_COUNT"

# ── Step 7: Toggle category status ───────────────────────────────────────────
section "Step 7: Toggle category active/inactive"
TOGGLE=$(curl -s -X PATCH "$BASE/api/v1/categories/$ROOT_CATEGORY_ID/toggle" \
  -H "Authorization: Bearer $TOKEN")
TOGGLE_SUCCESS=$(j "$TOGGLE" "'data' in d")
check "PATCH /categories/{id}/toggle returns data" "$TOGGLE_SUCCESS" "True"
check "Category is now inactive" "$(j "$TOGGLE" "d.get('data',{}).get('isActive',True)")" "False"

# Toggle back to active
TOGGLE_BACK=$(curl -s -X PATCH "$BASE/api/v1/categories/$ROOT_CATEGORY_ID/toggle" \
  -H "Authorization: Bearer $TOKEN")
check "Toggle back to active" "$(j "$TOGGLE_BACK" "d.get('data',{}).get('isActive',False)")" "True"

# ── Step 8: Verify final state ───────────────────────────────────────────────
section "Step 8: Final verification"
FINAL_CATEGORIES=$(curl -s -X GET "$BASE/api/v1/categories" \
  -H "Authorization: Bearer $TOKEN")
FINAL_COUNT=$(j "$FINAL_CATEGORIES" "len(d.get('data',[]))")
EXPECTED_COUNT=$((INITIAL_COUNT + 2))  # +2 for root and subcategory
check "Total categories count increased" "$FINAL_COUNT" "$EXPECTED_COUNT"

# ── Summary ───────────────────────────────────────────────────────────────────
section "Test Summary"
printf "\n"
if [ $FAIL -eq 0 ]; then
    printf "  \033[0;32m✓ ALL %d TESTS PASSED\033[0m\n" $PASS
else
    printf "  \033[0;31m✗ %d TESTS FAILED, %d PASSED\033[0m\n" $FAIL $PASS
fi
printf "\n"

exit $FAIL