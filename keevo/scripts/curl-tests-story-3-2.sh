#!/usr/bin/env bash
# ======================================================
# Story 3.2 — Vue Centralisée des Stocks Multi-Boutiques
# cURL E2E integration tests
# Run: bash curl-tests-story-3-2.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE="http://localhost:8080"
PY='python3 -c'

# ── Step 1 — Auth: register + 2-step login ───────────────────────────────
RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237611000042","password":"Test1234!","firstName":"Simon","lastName":"Overview"}')
LOGIN_TOKEN=$(echo "$RESP" | $PY "import sys,json; print(json.load(sys.stdin)['data']['loginToken'])")
TENANTS=$(curl -s -X GET "$BASE/api/v1/auth/tenants" -H "Authorization: Bearer $LOGIN_TOKEN")
TENANT_ID=$(echo "$TENANTS" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['tenantId'])")
SEL=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Authorization: Bearer $LOGIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT_ID\"}")
JWT=$(echo "$SEL" | $PY "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED"; exit 1; }

# ── Step 2 — GET /api/v1/stock/overview (no products yet = empty state OK) ─
OV=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
IS_ARRAY=$(echo "$OV" | $PY "import sys,json; d=json.load(sys.stdin); print(isinstance(d.get('data',[]), list))")
[[ "$IS_ARRAY" == "True" ]] && echo "✅ Step 2 — GET /stock/overview returns list" || { echo "❌ Step 2 FAILED"; exit 1; }

# ── Step 3 — Complete onboarding so storeId is provisioned ──────────────
# Only run if no stores are in the overview (fresh tenant)
STORE_COUNT=$(echo "$OV" | $PY "import sys,json; print(len(json.load(sys.stdin).get('data', [])))")
if [[ "$STORE_COUNT" -lt 1 ]]; then
  echo "⚠ Step 3 — No stores yet (onboarding needed). Triggering onboarding..."
  curl -s -X POST "$BASE/api/v1/onboarding/complete" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d '{"sectorType":"GENERAL","shopName":"Boutique Simon","city":"Yaoundé"}' > /dev/null || true
  OV=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
fi
STORE_COUNT=$(echo "$OV" | $PY "import sys,json; print(len(json.load(sys.stdin).get('data', [])))")
[[ "$STORE_COUNT" -ge 1 ]] && echo "✅ Step 3 — At least 1 store in overview (count=$STORE_COUNT)" || { echo "❌ Step 3 FAILED"; exit 1; }
FIRST_STORE_ID=$(echo "$OV" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['storeId'])")

# ── Step 4 — Create a product and add stock ──────────────────────────────
PROD=$(curl -s -X POST "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Chaussures Nike","price":25000,"buyPrice":15000,"stockQuantity":0,"categoryId":null}')
PROD_ID=$(echo "$PROD" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$PROD_ID" && "$PROD_ID" != "null" ]] && echo "✅ Step 4a — Product created (id=$PROD_ID)" || { echo "❌ Step 4a FAILED"; exit 1; }

# Add stock entry: 10 units
SE=$(curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$FIRST_STORE_ID\",\"quantity\":10,\"notes\":\"Arrivage Mars\"}")
QTY_AFTER=$(echo "$SE" | $PY "import sys,json; print(json.load(sys.stdin)['data']['quantityAfter'])")
[[ "$QTY_AFTER" == "10" ]] && echo "✅ Step 4b — Stock entry: 10 units" || { echo "❌ Step 4b FAILED (qty=$QTY_AFTER)"; exit 1; }

# ── Step 5 — GET /stock/overview reflects the new stock ──────────────────
OV2=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
PROD_COUNT=$(echo "$OV2" | $PY "
import sys,json
d=json.load(sys.stdin)['data']
store = next((s for s in d if s['storeId'] == '$FIRST_STORE_ID'), None)
print(store['productCount'] if store else -1)
")
TOTAL_VAL=$(echo "$OV2" | $PY "
import sys,json
d=json.load(sys.stdin)['data']
store = next((s for s in d if s['storeId'] == '$FIRST_STORE_ID'), None)
print(store['totalValueXaf'] if store else -1)
")
[[ "$PROD_COUNT" -ge 1 ]] && echo "✅ Step 5a — productCount >= 1 (count=$PROD_COUNT)" || { echo "❌ Step 5a FAILED (count=$PROD_COUNT)"; exit 1; }
[[ "$TOTAL_VAL" -ge 250000 ]] && echo "✅ Step 5b — totalValueXaf >= 250000 (10 × 25000 = $TOTAL_VAL XAF)" || { echo "❌ Step 5b FAILED (val=$TOTAL_VAL)"; exit 1; }

# ── Step 6 — Set threshold so low_stock triggers ─────────────────────────
curl -s -X PATCH "$BASE/api/v1/products/$PROD_ID/threshold" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"minimumThreshold":15}' > /dev/null  # threshold=15, stock=10 → BAS
OV3=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
LOW_COUNT=$(echo "$OV3" | $PY "
import sys,json
d=json.load(sys.stdin)['data']
store = next((s for s in d if s['storeId'] == '$FIRST_STORE_ID'), None)
print(store['lowStockCount'] if store else -1)
")
[[ "$LOW_COUNT" -ge 1 ]] && echo "✅ Step 6 — lowStockCount >= 1 (threshold=15, qty=10 → BAS, count=$LOW_COUNT)" || { echo "❌ Step 6 FAILED (lowCount=$LOW_COUNT)"; exit 1; }

# ── Step 7 — GET /stock/stores/{storeId}/products ────────────────────────
DET=$(curl -s -X GET "$BASE/api/v1/stock/stores/$FIRST_STORE_ID/products?page=0&size=25&sortLowFirst=true" \
  -H "Authorization: Bearer $JWT")
DET_TOTAL=$(echo "$DET" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
[[ "$DET_TOTAL" -ge 1 ]] && echo "✅ Step 7a — store detail: totalElements >= 1 (total=$DET_TOTAL)" || { echo "❌ Step 7a FAILED"; exit 1; }
FIRST_STATUS=$(echo "$DET" | $PY "import sys,json; print(json.load(sys.stdin)['data']['content'][0]['status'])")
[[ "$FIRST_STATUS" == "BAS" || "$FIRST_STATUS" == "CRITIQUE" ]] && echo "✅ Step 7b — sortLowFirst: first item is $FIRST_STATUS" || { echo "❌ Step 7b FAILED (status=$FIRST_STATUS)"; exit 1; }

# ── Step 8 — Pagination: size=1 → totalPages >= 1 ───────────────────────
DET_P=$(curl -s -X GET "$BASE/api/v1/stock/stores/$FIRST_STORE_ID/products?page=0&size=1" \
  -H "Authorization: Bearer $JWT")
TOTAL_PAGES=$(echo "$DET_P" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalPages'])")
[[ "$TOTAL_PAGES" -ge 1 ]] && echo "✅ Step 8 — Pagination: totalPages=$TOTAL_PAGES" || { echo "❌ Step 8 FAILED"; exit 1; }

# ── Step 9 — Create 2nd store + add stock in it ──────────────────────────
STORE2=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Bonanjo","type":"STORE"}')
STORE2_ID=$(echo "$STORE2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE2_ID\",\"quantity\":5,\"notes\":\"Transfert test\"}" > /dev/null
OV4=$(curl -s -X GET "$BASE/api/v1/stock/overview" -H "Authorization: Bearer $JWT")
OV4_COUNT=$(echo "$OV4" | $PY "import sys,json; print(len(json.load(sys.stdin)['data']))")
[[ "$OV4_COUNT" -ge 2 ]] && echo "✅ Step 9 — 2 stores in overview (count=$OV4_COUNT)" || { echo "❌ Step 9 FAILED (count=$OV4_COUNT)"; exit 1; }

# ── Step 10 — Invalid UUID → 400 ─────────────────────────────────────────
BAD_UUID=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stock/stores/not-a-uuid/products" \
  -H "Authorization: Bearer $JWT")
[[ "$BAD_UUID" == "400" ]] && echo "✅ Step 10 — Invalid UUID → 400" || { echo "❌ Step 10 FAILED (http=$BAD_UUID)"; exit 1; }

# ── Step 11 — No auth → 401 ──────────────────────────────────────────────
NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/stock/overview")
[[ "$NO_AUTH" == "401" ]] && echo "✅ Step 11 — 401 without auth" || { echo "❌ Step 11 FAILED (http=$NO_AUTH)"; exit 1; }

echo ""
echo "✅✅✅ All Story 3.2 cURL integration tests passed ✅✅✅"
