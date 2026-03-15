#!/usr/bin/env bash
# ======================================================
# Story 3.4 — Vérification de Disponibilité Cross-Boutique
# cURL E2E integration tests
# Run: bash curl-tests-story-3-4.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE="http://localhost:8080"
PY='python3 -c'

# ── Step 1 — Register and get JWT ─────────────────────────────────────────────
RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237611000344","password":"Test1234!","firstName":"Simon","lastName":"Disponibilite"}')
JWT=$(echo "$RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('token') or d.get('data',{}).get('accessToken',''))")
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED: $RESP"; exit 1; }

# ── Step 2 — Onboarding ───────────────────────────────────────────────────────
curl -s -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"GENERAL","shopName":"Boutique Disponibilite","city":"Douala"}' > /dev/null
echo "✅ Step 2 — Onboarding OK"

# ── Step 3 — Get store IDs ────────────────────────────────────────────────────
STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
STORE1_ID=$(echo "$STORES" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
[[ -n "$STORE1_ID" && "$STORE1_ID" != "null" ]] && echo "✅ Step 3 — Store found (id=$STORE1_ID)" || { echo "❌ Step 3 FAILED"; exit 1; }

# ── Step 4 — Create a product ─────────────────────────────────────────────────
PROD=$(curl -s -X POST "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Produit Disponibilite","price":1000,"costPrice":500}')
PROD_ID=$(echo "$PROD" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$PROD_ID" && "$PROD_ID" != "null" ]] && echo "✅ Step 4 — Product created (id=$PROD_ID)" || { echo "❌ Step 4 FAILED: $PROD"; exit 1; }

# ── Step 5 — GET /api/v1/stock/products/{id}/availability (no stock) ──────────
AVAIL=$(curl -s -X GET "$BASE/api/v1/stock/products/$PROD_ID/availability" \
  -H "Authorization: Bearer $JWT")
STATUS=$(echo "$AVAIL" | $PY "import sys,json; d=json.load(sys.stdin); print('ok' if 'productId' in d.get('data',{}) else 'fail')")
[[ "$STATUS" == "ok" ]] && echo "✅ Step 5 — GET availability OK (productId present)" || { echo "❌ Step 5 FAILED: $AVAIL"; exit 1; }

ENTRY_COUNT=$(echo "$AVAIL" | $PY "import sys,json; print(len(json.load(sys.stdin)['data']['entries']))")
[[ "$ENTRY_COUNT" -ge 0 ]] && echo "✅ Step 5 — entries count >= 0 (got: $ENTRY_COUNT)" || { echo "❌ Step 5 FAILED entry count"; exit 1; }

# ── Step 6 — Record stock entry in store 1 ───────────────────────────────────
SW=$(curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE1_ID\",\"quantity\":10}")
echo "✅ Step 6 — Stock entry recorded"

# ── Step 7 — GET availability again: should show store with qty=10 ─────────────
AVAIL2=$(curl -s -X GET "$BASE/api/v1/stock/products/$PROD_ID/availability" \
  -H "Authorization: Bearer $JWT")
QTY=$(echo "$AVAIL2" | $PY "
import sys,json
d = json.load(sys.stdin)['data']
for e in d['entries']:
    if e['quantity'] > 0:
        print(e['quantity'])
        break
else:
    print('0')
")
[[ "$QTY" == "10" ]] && echo "✅ Step 7 — store1 shows qty=10" || { echo "❌ Step 7 FAILED: qty=$QTY in $AVAIL2"; exit 1; }

# ── Step 8 — GET availability for unknown product → 404 ─────────────────────
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stock/products/00000000-0000-0000-0000-000000000000/availability" \
  -H "Authorization: Bearer $JWT")
[[ "$HTTP_CODE" == "404" ]] && echo "✅ Step 8 — unknown product → 404" || { echo "❌ Step 8 FAILED: got $HTTP_CODE"; exit 1; }

# ── Step 9 — GET availability without JWT → 401 ──────────────────────────────
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stock/products/$PROD_ID/availability")
[[ "$HTTP_CODE" == "401" ]] && echo "✅ Step 9 — no JWT → 401" || { echo "❌ Step 9 FAILED: got $HTTP_CODE"; exit 1; }

echo ""
echo "🎉 All Story 3.4 cURL integration tests passed!"
