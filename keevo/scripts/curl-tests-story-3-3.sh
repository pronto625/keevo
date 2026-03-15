#!/usr/bin/env bash
# ======================================================
# Story 3.3 — Transferts Inter-Boutiques avec Traçabilité
# cURL E2E integration tests
# Run: bash curl-tests-story-3-3.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE="http://localhost:8080"
PY='python3 -c'

# ── Step 1 — Register and get JWT ────────────────────────────────────────────
RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237611000133","password":"Test1234!","firstName":"Simon","lastName":"Transfer"}')
JWT=$(echo "$RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('token') or d.get('data',{}).get('accessToken',''))")
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED: $RESP"; exit 1; }

# ── Step 2 — Onboarding (creates default store) ──────────────────────────────
curl -s -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"GENERAL","shopName":"Boutique Transfert","city":"Douala"}' > /dev/null
echo "✅ Step 2 — Onboarding OK"

# ── Step 3 — Create a second store (destination) ─────────────────────────────
STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
STORE1_ID=$(echo "$STORES" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
[[ -n "$STORE1_ID" && "$STORE1_ID" != "null" ]] && echo "✅ Step 3a — Source store found (id=$STORE1_ID)" || { echo "❌ Step 3a FAILED"; exit 1; }

S2=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Bonanjo","type":"STORE"}')
STORE2_ID=$(echo "$S2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$STORE2_ID" && "$STORE2_ID" != "null" ]] && echo "✅ Step 3b — Destination store created (id=$STORE2_ID)" || { echo "❌ Step 3b FAILED: $S2"; exit 1; }

# ── Step 4 — Create product and add stock to source store ─────────────────────
PROD=$(curl -s -X POST "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Savon Azur 3.3","price":500,"buyPrice":300,"stockQuantity":0,"categoryId":null}')
PROD_ID=$(echo "$PROD" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$PROD_ID" && "$PROD_ID" != "null" ]] && echo "✅ Step 4a — Product created (id=$PROD_ID)" || { echo "❌ Step 4a FAILED: $PROD"; exit 1; }

SE=$(curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE1_ID\",\"quantity\":20,\"notes\":\"Stock initial\"}")
QTY=$(echo "$SE" | $PY "import sys,json; print(json.load(sys.stdin)['data']['quantityAfter'])")
[[ "$QTY" == "20" ]] && echo "✅ Step 4b — Stock entry: 20 units in source store" || { echo "❌ Step 4b FAILED (qty=$QTY): $SE"; exit 1; }

# ── Step 5 — Execute a transfer: 8 units from store1 → store2 ────────────────
TRANSFER=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE2_ID\",\"productId\":\"$PROD_ID\",\"quantity\":8,\"notes\":\"Transfert test\"}")
TRANSFER_ID=$(echo "$TRANSFER" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
TRANSFER_STATUS=$(echo "$TRANSFER" | $PY "import sys,json; print(json.load(sys.stdin)['data']['status'])")
[[ -n "$TRANSFER_ID" && "$TRANSFER_ID" != "null" ]] && echo "✅ Step 5a — Transfer created (id=$TRANSFER_ID)" || { echo "❌ Step 5a FAILED: $TRANSFER"; exit 1; }
[[ "$TRANSFER_STATUS" == "COMPLETED" ]] && echo "✅ Step 5b — Transfer status = COMPLETED" || { echo "❌ Step 5b FAILED (status=$TRANSFER_STATUS)"; exit 1; }

# ── Step 6 — Verify source stock decremented by 8 (20 - 8 = 12) ──────────────
SRC_STOCK=$(curl -s -X GET "$BASE/api/v1/products/$PROD_ID/stock" -H "Authorization: Bearer $JWT")
SRC_QTY=$(echo "$SRC_STOCK" | $PY "
import sys,json
levels = json.load(sys.stdin)['data']
src = next((l for l in levels if l.get('storeId') == '$STORE1_ID' or l.get('store',{}).get('id') == '$STORE1_ID'), None)
print(src['quantity'] if src else -1)
")
[[ "$SRC_QTY" == "12" ]] && echo "✅ Step 6 — Source stock: 20 - 8 = 12 ✓" || { echo "❌ Step 6 FAILED (srcQty=$SRC_QTY, expected 12): $SRC_STOCK"; exit 1; }

# ── Step 7 — Verify destination stock incremented by 8 (0 + 8 = 8) ───────────
DEST_QTY=$(echo "$SRC_STOCK" | $PY "
import sys,json
levels = json.load(sys.stdin)['data']
dst = next((l for l in levels if l.get('storeId') == '$STORE2_ID' or l.get('store',{}).get('id') == '$STORE2_ID'), None)
print(dst['quantity'] if dst else -1)
")
[[ "$DEST_QTY" == "8" ]] && echo "✅ Step 7 — Destination stock: 0 + 8 = 8 ✓" || { echo "❌ Step 7 FAILED (dstQty=$DEST_QTY, expected 8): $SRC_STOCK"; exit 1; }

# ── Step 8 — Verify two stock_movements created (TRANSFER_OUT + TRANSFER_IN) ──
HIST=$(curl -s -X GET "$BASE/api/v1/products/$PROD_ID/stock/history?page=0&size=10" \
  -H "Authorization: Bearer $JWT")
TYPES=$(echo "$HIST" | $PY "
import sys,json
d = json.load(sys.stdin)
content = d.get('data',{}).get('content', d.get('data', []))
types = sorted(set(m['movementType'] for m in content if 'TRANSFER' in m.get('movementType','').upper()))
print(','.join(types))
")
[[ "$TYPES" == "TRANSFER_IN,TRANSFER_OUT" ]] && echo "✅ Step 8 — Two movement records: TRANSFER_IN + TRANSFER_OUT" || { echo "❌ Step 8 FAILED (types=$TYPES)"; exit 1; }

# ── Step 9 — Transfer history endpoint ───────────────────────────────────────
HIST2=$(curl -s -X GET "$BASE/api/v1/stock/transfers?page=0&size=25" \
  -H "Authorization: Bearer $JWT")
TOTAL=$(echo "$HIST2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
[[ "$TOTAL" -ge 1 ]] && echo "✅ Step 9 — Transfer history: totalElements >= 1 (total=$TOTAL)" || { echo "❌ Step 9 FAILED (total=$TOTAL): $HIST2"; exit 1; }

# ── Step 10 — Filter by sourceStoreId ─────────────────────────────────────────
HIST_SRC=$(curl -s -X GET "$BASE/api/v1/stock/transfers?source=$STORE1_ID&page=0&size=25" \
  -H "Authorization: Bearer $JWT")
TOTAL_SRC=$(echo "$HIST_SRC" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
[[ "$TOTAL_SRC" -ge 1 ]] && echo "✅ Step 10 — Filtered by source: $TOTAL_SRC result(s)" || { echo "❌ Step 10 FAILED (total=$TOTAL_SRC)"; exit 1; }

# ── Step 11 — INSUFFICIENT_STOCK: transfer 100 units (only 12 remain) ─────────
OVTX=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE2_ID\",\"productId\":\"$PROD_ID\",\"quantity\":100}")
DOMAIN_CODE=$(echo "$OVTX" | $PY "import sys,json; print(json.load(sys.stdin).get('domainCode',''))")
[[ "$DOMAIN_CODE" == "INSUFFICIENT_STOCK" ]] && echo "✅ Step 11 — INSUFFICIENT_STOCK returned correctly" || { echo "❌ Step 11 FAILED (code=$DOMAIN_CODE): $OVTX"; exit 1; }

# ── Step 12 — SAME_SOURCE_DESTINATION: same store for source and dest ──────────
SAME=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE1_ID\",\"productId\":\"$PROD_ID\",\"quantity\":1}")
ERR_CODE=$(echo "$SAME" | $PY "import sys,json; print(json.load(sys.stdin).get('domainCode',''))")
[[ "$ERR_CODE" == "SAME_SOURCE_DESTINATION" || "$ERR_CODE" == "VALIDATION_ERROR" ]] && echo "✅ Step 12 — Same-store error returned (code=$ERR_CODE)" || { echo "❌ Step 12 FAILED (code=$ERR_CODE): $SAME"; exit 1; }

# ── Step 13 — Unauthenticated access returns 401 ──────────────────────────────
HTTP_401=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/stock/transfers")
[[ "$HTTP_401" == "401" ]] && echo "✅ Step 13 — 401 on unauthenticated GET /stock/transfers" || { echo "❌ Step 13 FAILED (code=$HTTP_401)"; exit 1; }

echo ""
echo "✅✅✅ All cURL integration checks passed — Story 3.3 backend validated ✅✅✅"


# ── Step 2 — Onboarding (creates default store) ──────────────────────────────
curl -s -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"GENERAL","shopName":"Boutique Transfert","city":"Douala"}' > /dev/null
echo "✅ Step 2 — Onboarding OK"

# ── Step 3 — Create a second store (destination) ─────────────────────────────
STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
STORE1_ID=$(echo "$STORES" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
[[ -n "$STORE1_ID" && "$STORE1_ID" != "null" ]] && echo "✅ Step 3a — Source store found (id=$STORE1_ID)" || { echo "❌ Step 3a FAILED"; exit 1; }

S2=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Bonanjo","type":"STORE"}')
STORE2_ID=$(echo "$S2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$STORE2_ID" && "$STORE2_ID" != "null" ]] && echo "✅ Step 3b — Destination store created (id=$STORE2_ID)" || { echo "❌ Step 3b FAILED"; exit 1; }

# ── Step 4 — Create product and add stock to source store ─────────────────────
PROD=$(curl -s -X POST "$BASE/api/v1/products" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Savon Azur","price":500,"buyPrice":300,"stockQuantity":0,"categoryId":null}')
PROD_ID=$(echo "$PROD" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$PROD_ID" && "$PROD_ID" != "null" ]] && echo "✅ Step 4a — Product created (id=$PROD_ID)" || { echo "❌ Step 4a FAILED"; exit 1; }

SE=$(curl -s -X POST "$BASE/api/v1/products/$PROD_ID/stock/entry" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE1_ID\",\"quantity\":20,\"notes\":\"Stock initial\"}")
QTY=$(echo "$SE" | $PY "import sys,json; print(json.load(sys.stdin)['data']['quantityAfter'])")
[[ "$QTY" == "20" ]] && echo "✅ Step 4b — Stock entry: 20 units in source store" || { echo "❌ Step 4b FAILED (qty=$QTY)"; exit 1; }

# ── Step 5 — Execute a transfer: 8 units from store1 → store2 ────────────────
TRANSFER=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE2_ID\",\"productId\":\"$PROD_ID\",\"quantity\":8,\"notes\":\"Transfert test\"}")
TRANSFER_ID=$(echo "$TRANSFER" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
TRANSFER_STATUS=$(echo "$TRANSFER" | $PY "import sys,json; print(json.load(sys.stdin)['data']['status'])")
[[ -n "$TRANSFER_ID" && "$TRANSFER_ID" != "null" ]] && echo "✅ Step 5a — Transfer created (id=$TRANSFER_ID)" || { echo "❌ Step 5a FAILED"; exit 1; }
[[ "$TRANSFER_STATUS" == "COMPLETED" ]] && echo "✅ Step 5b — Transfer status = COMPLETED" || { echo "❌ Step 5b FAILED (status=$TRANSFER_STATUS)"; exit 1; }

# ── Step 6 — Verify source stock decremented by 8 (20 - 8 = 12) ──────────────
SRC_STOCK=$(curl -s -X GET "$BASE/api/v1/products/$PROD_ID/stock" -H "Authorization: Bearer $JWT")
SRC_QTY=$(echo "$SRC_STOCK" | $PY "
import sys,json
levels = json.load(sys.stdin)['data']
src = next((l for l in levels if l['storeId'] == '$STORE1_ID'), None)
print(src['quantity'] if src else -1)
")
[[ "$SRC_QTY" == "12" ]] && echo "✅ Step 6 — Source stock: 20 - 8 = 12 ✓" || { echo "❌ Step 6 FAILED (srcQty=$SRC_QTY, expected 12)"; exit 1; }

# ── Step 7 — Verify destination stock incremented by 8 (0 + 8 = 8) ───────────
DEST_QTY=$(echo "$SRC_STOCK" | $PY "
import sys,json
levels = json.load(sys.stdin)['data']
dst = next((l for l in levels if l['storeId'] == '$STORE2_ID'), None)
print(dst['quantity'] if dst else -1)
")
[[ "$DEST_QTY" == "8" ]] && echo "✅ Step 7 — Destination stock: 0 + 8 = 8 ✓" || { echo "❌ Step 7 FAILED (dstQty=$DEST_QTY, expected 8)"; exit 1; }

# ── Step 8 — Verify two stock_movements created (TRANSFER_OUT + TRANSFER_IN) ──
HIST=$(curl -s -X GET "$BASE/api/v1/products/$PROD_ID/stock/history?page=0&size=10" \
  -H "Authorization: Bearer $JWT")
TYPES=$(echo "$HIST" | $PY "
import sys,json
content = json.load(sys.stdin)['data']['content']
types = sorted(set(m['movementType'] for m in content if m['movementType'].startswith('TRANSFER')))
print(','.join(types))
")
[[ "$TYPES" == "TRANSFER_IN,TRANSFER_OUT" ]] && echo "✅ Step 8 — Two movement records: TRANSFER_IN + TRANSFER_OUT" || { echo "❌ Step 8 FAILED (types=$TYPES)"; exit 1; }

# ── Step 9 — Transfer history endpoint ───────────────────────────────────────
HIST2=$(curl -s -X GET "$BASE/api/v1/stock/transfers?page=0&size=25" \
  -H "Authorization: Bearer $JWT")
TOTAL=$(echo "$HIST2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
[[ "$TOTAL" -ge 1 ]] && echo "✅ Step 9 — Transfer history: totalElements >= 1 (total=$TOTAL)" || { echo "❌ Step 9 FAILED (total=$TOTAL)"; exit 1; }

# ── Step 10 — Filter by sourceStoreId ─────────────────────────────────────────
HIST_SRC=$(curl -s -X GET "$BASE/api/v1/stock/transfers?source=$STORE1_ID&page=0&size=25" \
  -H "Authorization: Bearer $JWT")
TOTAL_SRC=$(echo "$HIST_SRC" | $PY "import sys,json; print(json.load(sys.stdin)['data']['totalElements'])")
[[ "$TOTAL_SRC" -ge 1 ]] && echo "✅ Step 10 — Filtered by source: $TOTAL_SRC result(s)" || { echo "❌ Step 10 FAILED (total=$TOTAL_SRC)"; exit 1; }

# ── Step 11 — INSUFFICIENT_STOCK: transfer 100 units (only 12 remain) ─────────
OVTX=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE2_ID\",\"productId\":\"$PROD_ID\",\"quantity\":100}")
DOMAIN_CODE=$(echo "$OVTX" | $PY "import sys,json; print(json.load(sys.stdin).get('domainCode',''))")
[[ "$DOMAIN_CODE" == "INSUFFICIENT_STOCK" ]] && echo "✅ Step 11 — INSUFFICIENT_STOCK returned correctly" || { echo "❌ Step 11 FAILED (code=$DOMAIN_CODE)"; exit 1; }

# ── Step 12 — SAME_SOURCE_DESTINATION: same store for source and dest ──────────
SAME=$(curl -s -X POST "$BASE/api/v1/stock/transfers" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"sourceStoreId\":\"$STORE1_ID\",\"destinationStoreId\":\"$STORE1_ID\",\"productId\":\"$PROD_ID\",\"quantity\":1}")
ERR_CODE=$(echo "$SAME" | $PY "import sys,json; print(json.load(sys.stdin).get('domainCode',''))")
[[ "$ERR_CODE" == "SAME_SOURCE_DESTINATION" || "$ERR_CODE" == "VALIDATION_ERROR" ]] && echo "✅ Step 12 — Same-store error returned" || { echo "❌ Step 12 FAILED (code=$ERR_CODE)"; exit 1; }

# ── Step 13 — Unauthenticated access returns 401 ──────────────────────────────
HTTP_401=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/stock/transfers")
[[ "$HTTP_401" == "401" ]] && echo "✅ Step 13 — 401 on unauthenticated GET /stock/transfers" || { echo "❌ Step 13 FAILED (code=$HTTP_401)"; exit 1; }

echo ""
echo "✅✅✅ All cURL integration checks passed — Story 3.3 backend validated ✅✅✅"
