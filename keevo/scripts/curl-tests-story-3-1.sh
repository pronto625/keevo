#!/usr/bin/env bash
# ======================================================
# Story 3.1 — Stores & Warehouse E2E curl tests
# Run: bash curl-tests-story-3-1.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE="http://localhost:8080"
PY='python3 -c'

# Step 1 — Register + Login + Select Tenant
RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237611000031","password":"Test1234!","firstName":"Simon","lastName":"Store"}')
ACCESS=$(echo "$RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['loginToken'])")
RESP2=$(curl -s -X GET "$BASE/api/v1/auth/tenants" -H "Authorization: Bearer $ACCESS")
TENANT=$(echo "$RESP2" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data'][0]['tenantId'])")
RESP3=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Authorization: Bearer $ACCESS" \
  -H "Content-Type: application/json" \
  -d "{\"tenantId\":\"$TENANT\"}")
JWT=$(echo "$RESP3" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['accessToken'])")
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1 — Auth OK" || { echo "❌ Step 1 FAILED"; exit 1; }

# Step 2 — GET /api/v1/stores → should return 1 store (seeded by onboarding)
STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
COUNT=$(echo "$STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(len(d['data']))")
[[ "$COUNT" == "1" ]] && echo "✅ Step 2 — 1 default store exists" || { echo "❌ Step 2 FAILED (count=$COUNT)"; exit 1; }
STORE1_ID=$(echo "$STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data'][0]['id'])")

# Step 3 — PATCH /api/v1/stores/{storeId} — edit store name
UPDATED=$(curl -s -X PATCH "$BASE/api/v1/stores/$STORE1_ID" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Centrale","address":"Rue des marchés, Yaoundé","phone":"+237690000001"}')
NAME=$(echo "$UPDATED" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['name'])")
[[ "$NAME" == "Boutique Centrale" ]] && echo "✅ Step 3 — Store updated" || { echo "❌ Step 3 FAILED (name=$NAME)"; exit 1; }

# Step 4 — POST /api/v1/stores — create 2nd store (STORE type)
S2=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Bonanjo","type":"STORE","address":"Bonanjo, Douala"}')
S2_ID=$(echo "$S2" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['id'])")
[[ -n "$S2_ID" && "$S2_ID" != "null" ]] && echo "✅ Step 4 — 2nd store created (id=$S2_ID)" || { echo "❌ Step 4 FAILED"; exit 1; }

# Step 5 — POST /api/v1/stores — create warehouse
WH=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Entrepôt Principal","type":"WAREHOUSE"}')
WH_ID=$(echo "$WH" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['id'])")
WH_TYPE=$(echo "$WH" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['type'])")
[[ "$WH_TYPE" == "WAREHOUSE" ]] && echo "✅ Step 5 — Warehouse created (id=$WH_ID)" || { echo "❌ Step 5 FAILED (type=$WH_TYPE)"; exit 1; }

# Step 6 — POST /api/v1/stores with type WAREHOUSE again → should return 409
WH2=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Second Entrepôt","type":"WAREHOUSE"}')
[[ "$WH2" == "409" ]] && echo "✅ Step 6 — 409 WAREHOUSE_ALREADY_EXISTS" || { echo "❌ Step 6 FAILED (http=$WH2)"; exit 1; }

# Step 7 — POST /api/v1/stores with 4th store → should return 403 PLAN_LIMIT_EXCEEDED
# (Free plan = 3 stores max; we have 3 active: store1, store2, warehouse)
LIMIT=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique 4","type":"STORE"}')
LIMIT_CODE=$(echo "$LIMIT" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('domainCode',''))")
[[ "$LIMIT_CODE" == "PLAN_LIMIT_EXCEEDED" ]] && echo "✅ Step 7 — 403 PLAN_LIMIT_EXCEEDED on 4th store" || { echo "❌ Step 7 FAILED (code=$LIMIT_CODE)"; exit 1; }

# Step 8 — PATCH /api/v1/stores/{storeId}/deactivate — deactivate 2nd store
DEACT=$(curl -s -X PATCH "$BASE/api/v1/stores/$S2_ID/deactivate" \
  -H "Authorization: Bearer $JWT")
ACTIVE=$(echo "$DEACT" | $PY "import sys,json; d=json.load(sys.stdin); print(d['data']['isActive'])")
[[ "$ACTIVE" == "False" || "$ACTIVE" == "false" ]] && echo "✅ Step 8 — Store deactivated" || { echo "❌ Step 8 FAILED (isActive=$ACTIVE)"; exit 1; }

# Step 9 — GET /api/v1/stores → 2 active stores (store1 + warehouse)
ACTIVE_STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
ACTIVE_COUNT=$(echo "$ACTIVE_STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(len(d['data']))")
[[ "$ACTIVE_COUNT" == "2" ]] && echo "✅ Step 9 — 2 active stores after deactivating S2" || { echo "❌ Step 9 FAILED (count=$ACTIVE_COUNT)"; exit 1; }

# Step 10 — GET /api/v1/stores?includeInactive=true → 3 stores
ALL_STORES=$(curl -s -X GET "$BASE/api/v1/stores?includeInactive=true" -H "Authorization: Bearer $JWT")
ALL_COUNT=$(echo "$ALL_STORES" | $PY "import sys,json; d=json.load(sys.stdin); print(len(d['data']))")
[[ "$ALL_COUNT" == "3" ]] && echo "✅ Step 10 — 3 total stores (incl. inactive)" || { echo "❌ Step 10 FAILED (count=$ALL_COUNT)"; exit 1; }

# Step 11 — POST /api/v1/stores without JWT → 401
NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/stores" \
  -H "Content-Type: application/json" \
  -d '{"name":"Unauthorized Store"}')
[[ "$NO_AUTH" == "401" ]] && echo "✅ Step 11 — 401 without auth" || { echo "❌ Step 11 FAILED (http=$NO_AUTH)"; exit 1; }

# Step 12 — PATCH /api/v1/stores/{storeId} with blank name → 400
BAD=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$BASE/api/v1/stores/$STORE1_ID" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":""}')
[[ "$BAD" == "400" || "$BAD" == "422" ]] && echo "✅ Step 12 — 400/422 on blank name" || { echo "❌ Step 12 FAILED (http=$BAD)"; exit 1; }

echo ""
echo "✅✅✅ All cURL integration checks passed — story 3.1 backend validated ✅✅✅"
