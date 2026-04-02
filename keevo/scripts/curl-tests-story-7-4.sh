#!/usr/bin/env bash
# ======================================================
# Story 7.4 — cURL Integration Tests
# Dashboard Rentabilité — analyse par produit & boutique
# Run: bash curl-tests-story-7-4.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail

BASE_URL="http://localhost:8080"
PHONE="+237600074001"
PASSWORD="Test7400!"
TODAY=$(date +%Y-%m-%d)

echo "═══════════════════════════════════════════════════"
echo "  Story 7.4 — Dashboard Rentabilité cURL Tests"
echo "═══════════════════════════════════════════════════"

# ─────────────────────────────────────────────────────────
# ITERATION 1 — Auth: login + select-tenant
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 1 — Auth (login + select-tenant) ──"

LOGIN_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASSWORD\"}")

PRE_TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('loginToken',''))" 2>/dev/null || true)
TENANT_CODE=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; m=json.load(sys.stdin).get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null || true)

echo "  PRE_TOKEN obtained: ${#PRE_TOKEN} chars"
echo "  TENANT_CODE: $TENANT_CODE"

if [[ -z "$PRE_TOKEN" || "$PRE_TOKEN" == "null" ]]; then
  echo "  ❌ Iteration 1 FAILED — no loginToken"
  echo "  RAW: $LOGIN_RESP"
  exit 1
fi

TENANT_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$PRE_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")

TOKEN=$(echo "$TENANT_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('data',{}).get('accessToken',''))" 2>/dev/null || true)

echo "  OWNER TOKEN obtained: ${#TOKEN} chars"

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo "  ❌ Iteration 1 FAILED — no final owner token"
  echo "  RAW: $TENANT_RESP"
  exit 1
fi

echo "  ✅ Iteration 1 — OWNER token obtained"

# ─────────────────────────────────────────────────────────
# ITERATION 2 — Empty period (ancient dates, no sales)
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 2 — Empty period → 200 + empty list ──"

EMPTY=$(curl -s -w "\n%{http_code}" \
  "$BASE_URL/api/v1/reporting/profitability/products?from=2020-01-01&to=2020-01-31" \
  -H "Authorization: Bearer $TOKEN")
HTTP_CODE=$(echo "$EMPTY" | tail -1)
BODY=$(echo "$EMPTY" | head -1)

echo "  HTTP $HTTP_CODE"
echo "$BODY" | python3 -m json.tool 2>/dev/null || echo "$BODY"

if [[ "$HTTP_CODE" == "200" ]]; then
  echo "  ✅ Iteration 2 — 200 OK, empty period returns list"
else
  echo "  ❌ Iteration 2 FAILED — expected 200, got $HTTP_CODE"
fi

# ─────────────────────────────────────────────────────────
# ITERATION 3 — Register a sale via POS (to have data)
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 3 — Create a sale to have profitability data ──"

# Get list of products
PRODUCTS_RESP=$(curl -s \
  "$BASE_URL/api/v1/products?page=0&size=3" \
  -H "Authorization: Bearer $TOKEN")
echo "$PRODUCTS_RESP" | python3 -m json.tool 2>/dev/null | head -10 || echo "$PRODUCTS_RESP" | head -100

PRODUCT_COUNT=$(echo "$PRODUCTS_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
content = data if isinstance(data, list) else data.get('content', [])
print(len(content))
" 2>/dev/null || echo "0")
echo "  Products found: $PRODUCT_COUNT"

if [[ "$PRODUCT_COUNT" -lt "1" ]]; then
  echo "  ⚠️  No products found — creating one on the fly"
  # Correct field names: price, buyPrice, transportCost, stockQuantity
  PROD_CREATE=$(curl -s -X POST "$BASE_URL/api/v1/products" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"name":"Coca-Cola 50cl","price":500,"buyPrice":300,"transportCost":50,"stockQuantity":100}')
  echo "$PROD_CREATE" | python3 -m json.tool 2>/dev/null | head -8 || true
  # Re-fetch products list so P1/P2 extraction below works
  PRODUCTS_RESP=$(curl -s "$BASE_URL/api/v1/products?page=0&size=3" -H "Authorization: Bearer $TOKEN")
fi

# Extract first product ID
P1=$(echo "$PRODUCTS_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
content = data if isinstance(data, list) else data.get('content', [])
print(content[0]['id'] if content else '')
" 2>/dev/null || true)

P2=$(echo "$PRODUCTS_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
content = data if isinstance(data, list) else data.get('content', [])
print(content[1]['id'] if len(content) > 1 else '')
" 2>/dev/null || true)

echo "  P1: $P1"
echo "  P2: $P2"

# Get store ID
STORES_RESP=$(curl -s "$BASE_URL/api/v1/stores" -H "Authorization: Bearer $TOKEN")
STORE_ID=$(echo "$STORES_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
stores = d.get('data', [])
if isinstance(stores, list) and len(stores) > 0:
    print(stores[0]['id'])
else:
    print('')
" 2>/dev/null || true)
echo "  STORE_ID: $STORE_ID"

if [[ -n "$P1" && -n "$STORE_ID" ]]; then
  # Fix product data: ensure price and stock are set correctly (in case product
  # was created with wrong field names in a previous run)
  echo "  Patching product price+stock for test data..."
  curl -s -X PATCH "$BASE_URL/api/v1/products/$P1" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d '{"price":500,"buyPrice":300,"transportCost":50}' > /dev/null 2>&1 || true

  # Ensure stock is available in the store
  ADJUST_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE_URL/api/v1/products/$P1/stock/adjust" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"storeId\":\"$STORE_ID\",\"newQuantity\":100,\"notes\":\"Test stock for 7.4\"}")
  echo "  Stock adjust HTTP: $ADJUST_HTTP"

  # Extract product name and catalogue price from products response
  P1_NAME=$(echo "$PRODUCTS_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
c = data if isinstance(data, list) else data.get('content', [])
print(c[0].get('name', 'Product') if c else 'Product')
" 2>/dev/null || echo "Product")
  P1_PRICE=$(echo "$PRODUCTS_RESP" | python3 -c "
import sys, json
d = json.load(sys.stdin)
data = d.get('data', [])
c = data if isinstance(data, list) else data.get('content', [])
p = c[0] if c else {}
v = p.get('cataloguePrice') or p.get('price') or p.get('sellingPrice') or 0
print(v if v and v > 0 else 500)
" 2>/dev/null || echo "500")
  echo "  P1: $P1 ($P1_NAME, price=$P1_PRICE)"

  # Build items — POS requires productName, catalogueUnitPrice, appliedUnitPrice
  SALE_UUID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
  ITEMS="[{\"productId\":\"$P1\",\"productName\":\"$P1_NAME\",\"catalogueUnitPrice\":$P1_PRICE,\"appliedUnitPrice\":$P1_PRICE,\"quantity\":2}]"

  SALE_RESP=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"saleId\":\"$SALE_UUID\",\"storeId\":\"$STORE_ID\",\"items\":$ITEMS,\"paymentMode\":\"CASH\",\"discountAmount\":0}")
  SALE_HTTP=$(echo "$SALE_RESP" | tail -1)
  SALE_BODY=$(echo "$SALE_RESP" | head -1)
  echo "  POS sale HTTP: $SALE_HTTP"
  echo "$SALE_BODY" | python3 -m json.tool 2>/dev/null | head -8 || echo "$SALE_BODY" | head -100

  if [[ "$SALE_HTTP" == "200" || "$SALE_HTTP" == "201" ]]; then
    echo "  ✅ Iteration 3 — Sale created (HTTP $SALE_HTTP)"
  else
    echo "  ⚠️  Iteration 3 — Sale HTTP $SALE_HTTP (proceeding with existing data)"
  fi
else
  echo "  ⚠️  Iteration 3 — Missing P1 or STORE_ID; skipping sale creation"
fi

# ─────────────────────────────────────────────────────────
# ITERATION 4 — Profitability list, default sort
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 4 — Product profitability list, default sort ──"

LIST_RESP=$(curl -s -w "\n%{http_code}" \
  "$BASE_URL/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN")
LIST_HTTP=$(echo "$LIST_RESP" | tail -1)
LIST_BODY=$(echo "$LIST_RESP" | head -1)

echo "  HTTP $LIST_HTTP"
echo "$LIST_BODY" | python3 -m json.tool 2>/dev/null || echo "$LIST_BODY"

if [[ "$LIST_HTTP" == "200" ]]; then
  # Verify expected fields exist in entries
  FIELDS_OK=$(echo "$LIST_BODY" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    entries = d.get('data', [])
    if not isinstance(entries, list) or len(entries) == 0:
        print('empty_ok')
    else:
        e = entries[0]
        required = ['productId','productName','unitsSold','totalRevenue','totalCost','grossMarginXaf','marginPercent','isLoss']
        missing = [f for f in required if f not in e]
        print('missing:' + str(missing) if missing else 'ok:' + str(len(entries)) + '_entries')
except Exception as ex:
    print('parse_error:' + str(ex))
" 2>/dev/null || echo "parse_failed")
  echo "  Fields check: $FIELDS_OK"
  echo "  ✅ Iteration 4 — 200 OK, product list returned"
else
  echo "  ❌ Iteration 4 FAILED — expected 200, got $LIST_HTTP"
fi

# ─────────────────────────────────────────────────────────
# ITERATION 5 — Profitability list: all sort options
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 5 — Product list: all sort options ──"

ALL_SORTS_OK=true
for SORT in "MARGIN_PCT_DESC" "MARGIN_XAF_DESC" "CA_DESC" "UNITS_DESC"; do
  SORT_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY&sort=$SORT" \
    -H "Authorization: Bearer $TOKEN")
  if [[ "$SORT_HTTP" == "200" ]]; then
    echo "  ✅ sort=$SORT → $SORT_HTTP"
  else
    echo "  ❌ sort=$SORT → $SORT_HTTP (expected 200)"
    ALL_SORTS_OK=false
  fi
done

$ALL_SORTS_OK && echo "  ✅ Iteration 5 — All 4 sort options return 200" || echo "  ❌ Iteration 5 — Some sort options failed"

# ─────────────────────────────────────────────────────────
# ITERATION 6 — Product detail
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 6 — Product profitability detail ──"

if [[ -n "$P1" ]]; then
  DETAIL_RESP=$(curl -s -w "\n%{http_code}" \
    "$BASE_URL/api/v1/reporting/profitability/products/$P1?from=2026-01-01&to=$TODAY" \
    -H "Authorization: Bearer $TOKEN")
  DETAIL_HTTP=$(echo "$DETAIL_RESP" | tail -1)
  DETAIL_BODY=$(echo "$DETAIL_RESP" | head -1)

  echo "  HTTP $DETAIL_HTTP"
  echo "$DETAIL_BODY" | python3 -m json.tool 2>/dev/null || echo "$DETAIL_BODY"

  if [[ "$DETAIL_HTTP" == "200" ]]; then
    DETAIL_FIELDS=$(echo "$DETAIL_BODY" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    e = d.get('data', {})
    required = ['productId','productName','unitsSold','totalRevenue','totalCost',
                'grossMarginXaf','marginPercent','currentCataloguePrice','currentBuyPrice',
                'currentTransportCost','minAppliedPrice','maxAppliedPrice','avgAppliedPrice',
                'dailyMarginLast7','topStoreName','topStoreUnitsSold']
    missing = [f for f in required if f not in e]
    print('missing:' + str(missing) if missing else 'all_fields_present')
except Exception as ex:
    print('error:' + str(ex))
" 2>/dev/null || echo "parse_failed")
    echo "  Fields check: $DETAIL_FIELDS"
    echo "  ✅ Iteration 6 — Product detail 200 OK"
  else
    echo "  ❌ Iteration 6 FAILED — expected 200, got $DETAIL_HTTP"
  fi
else
  echo "  ⚠️  Iteration 6 SKIPPED — no P1 available"
fi

# ─────────────────────────────────────────────────────────
# ITERATION 7 — Product detail: unknown productId → 404
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 7 — Product detail: unknown ID → 404 ──"

NOT_FOUND_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/reporting/profitability/products/00000000-0000-0000-0000-000000000000?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN")

if [[ "$NOT_FOUND_HTTP" == "404" ]]; then
  echo "  ✅ Iteration 7 — Unknown product → 404"
else
  echo "  ❌ Iteration 7 FAILED — expected 404, got $NOT_FOUND_HTTP"
fi

# ─────────────────────────────────────────────────────────
# ITERATION 8 — Store performance + all metric options
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 8 — Store performance + all metrics ──"

STORES_PERF_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY" \
  -H "Authorization: Bearer $TOKEN")

if [[ "$STORES_PERF_HTTP" == "200" ]]; then
  echo "  ✅ stores default → 200"
else
  echo "  ❌ stores default → $STORES_PERF_HTTP (expected 200)"
fi

ALL_METRICS_OK=true
for METRIC in "CA" "SALES_COUNT" "AVG_BASKET"; do
  M_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY&metric=$METRIC" \
    -H "Authorization: Bearer $TOKEN")
  if [[ "$M_HTTP" == "200" ]]; then
    echo "  ✅ metric=$METRIC → $M_HTTP"
  else
    echo "  ❌ metric=$METRIC → $M_HTTP (expected 200)"
    ALL_METRICS_OK=false
  fi
done

$ALL_METRICS_OK && echo "  ✅ Iteration 8 — All 3 store metrics return 200" || echo "  ❌ Iteration 8 — Some metrics failed"

# ─────────────────────────────────────────────────────────
# ITERATION 9 — RBAC: no token → 401, employee → 403
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 9 — RBAC: unauthenticated → 401 ──"

NO_AUTH_P=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY")
NO_AUTH_S=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY")

[[ "$NO_AUTH_P" == "401" ]] && echo "  ✅ /products no auth → 401" || echo "  ❌ /products no auth → $NO_AUTH_P (expected 401)"
[[ "$NO_AUTH_S" == "401" ]] && echo "  ✅ /stores no auth → 401" || echo "  ❌ /stores no auth → $NO_AUTH_S (expected 401)"

# EMPLOYEE → 403: create employee in the owner's tenant via POST /api/v1/employees
echo ""
echo "── Iteration 9b — RBAC: employee token → 403 ──"

if [[ -n "$STORE_ID" ]]; then
  # Use a unique phone per run to avoid conflicts
  EMP_PHONE="+23760007409$(date +%S)"
  # Create employee via owner's tenant (returns temp password)
  EMP_CREATE=$(curl -s -X POST "$BASE_URL/api/v1/employees" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $TOKEN" \
    -d "{\"firstName\":\"EmpTest\",\"lastName\":\"74\",\"phoneNumber\":\"$EMP_PHONE\",\"storeId\":\"$STORE_ID\"}")
  EMP_TEMP_PASS=$(echo "$EMP_CREATE" | python3 -c "
import sys, json
print(json.load(sys.stdin).get('data', {}).get('temporaryPassword', ''))
" 2>/dev/null || true)
  echo "  Employee create temp_pass: ${#EMP_TEMP_PASS} chars"

  if [[ -n "$EMP_TEMP_PASS" && "$EMP_TEMP_PASS" != "null" ]]; then
    EMP_LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
      -H "Content-Type: application/json" \
      -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"$EMP_TEMP_PASS\"}")
    EMP_PRE=$(echo "$EMP_LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin).get('loginToken',''))" 2>/dev/null || true)
    EMP_TENANT=$(echo "$EMP_LOGIN" | python3 -c "import sys,json; m=json.load(sys.stdin).get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null || true)
    echo "  Employee pre-token: ${#EMP_PRE} chars, tenant: $EMP_TENANT"

    EMP_FINAL_RESP=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
      -H "Content-Type: application/json" \
      -d "{\"loginToken\":\"$EMP_PRE\",\"tenantCode\":\"$EMP_TENANT\"}")
    EMP_TOKEN=$(echo "$EMP_FINAL_RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('data',{}).get('accessToken',''))" 2>/dev/null || true)
    echo "  Employee final token: ${#EMP_TOKEN} chars"

    if [[ -n "$EMP_TOKEN" && "$EMP_TOKEN" != "null" ]]; then
      EMP_P=$(curl -s -o /dev/null -w "%{http_code}" \
        "$BASE_URL/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY" \
        -H "Authorization: Bearer $EMP_TOKEN")
      EMP_S=$(curl -s -o /dev/null -w "%{http_code}" \
        "$BASE_URL/api/v1/reporting/profitability/stores?from=2026-01-01&to=$TODAY" \
        -H "Authorization: Bearer $EMP_TOKEN")
      [[ "$EMP_P" == "403" ]] && echo "  ✅ Employee /products → 403" || echo "  ❌ Employee /products → $EMP_P (expected 403)"
      [[ "$EMP_S" == "403" ]] && echo "  ✅ Employee /stores → 403" || echo "  ❌ Employee /stores → $EMP_S (expected 403)"
    else
      echo "  ⚠️  Iteration 9b SKIPPED — could not obtain employee final token"
    fi
  else
    echo "  ⚠️  Iteration 9b SKIPPED — employee creation returned no temp password"
    echo "  RAW: $EMP_CREATE"
  fi
else
  echo "  ⚠️  Iteration 9b SKIPPED — no STORE_ID available"
fi

# ─────────────────────────────────────────────────────────
# ITERATION 10 — storeId filter
# ─────────────────────────────────────────────────────────
echo ""
echo "── Iteration 10 — storeId filter ──"

if [[ -n "$STORE_ID" ]]; then
  FILTER_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
    "$BASE_URL/api/v1/reporting/profitability/products?from=2026-01-01&to=$TODAY&storeId=$STORE_ID" \
    -H "Authorization: Bearer $TOKEN")
  if [[ "$FILTER_HTTP" == "200" ]]; then
    echo "  ✅ Iteration 10 — storeId filter → 200 OK"
  else
    echo "  ❌ Iteration 10 FAILED — expected 200, got $FILTER_HTTP"
  fi
else
  echo "  ⚠️  Iteration 10 SKIPPED — no STORE_ID available"
fi

# ─────────────────────────────────────────────────────────
# SUMMARY
# ─────────────────────────────────────────────────────────
echo ""
echo "═══════════════════════════════════════════════════"
echo "  Story 7.4 cURL integration tests done!"
echo "  Review ✅/❌/⚠️  markers above."
echo "═══════════════════════════════════════════════════"
