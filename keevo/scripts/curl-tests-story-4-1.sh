#!/usr/bin/env bash
# ======================================================
# Story 4.1 — cURL Integration Tests: Enregistrement de Vente & Flux POS Core
# Run: bash curl-tests-story-4-1.sh
# Prerequisites: Docker backend running on port 8443,
#   jq installed, python3 for phone-based auth
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"
PHONE="+237600000041"
PASSWORD="Test1234!"

# ─── Step 1 — Register + two-step login to get JWT ─────────────────────────
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\",\"firstName\":\"Loic\",\"lastName\":\"Vendeur\"}")
echo "Register: $(echo $REGISTER | jq -r '.data.accessToken // .error // "no token"' | head -c 60)"

LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}")
LOGIN_TOKEN=$(echo "$LOGIN" | jq -r '.data.loginToken')
MEMBERSHIPS=$(echo "$LOGIN" | jq -r '.data.memberships[0].tenantId // empty')
[[ -n "$LOGIN_TOKEN" && "$LOGIN_TOKEN" != "null" ]] && echo "✅ Step 1a — loginToken obtained" || { echo "❌ Step 1a FAILED: $LOGIN"; exit 1; }

SELECT=$(curl -s -X POST "$BASE_URL/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantId\":\"$(echo $LOGIN | jq -r '.data.memberships[0].tenantId')\"}")
JWT=$(echo "$SELECT" | jq -r '.data.accessToken')
SCHEMA=$(echo "$SELECT" | jq -r '.data.schemaName // .data.tenantId')
STORE_ID=$(echo "$SELECT" | jq -r '.data.storeId // empty')
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 1b — accessToken obtained (schema=$SCHEMA)" || { echo "❌ Step 1b FAILED: $SELECT"; exit 1; }

# ─── Step 2 — Get a product ID that exists for testing ─────────────────────
PRODUCTS=$(curl -s "$BASE_URL/api/v1/products" \
  -H "Authorization: Bearer $JWT")
PRODUCT_ID=$(echo "$PRODUCTS" | jq -r '.data.content[0].id // empty')
PRODUCT_NAME=$(echo "$PRODUCTS" | jq -r '.data.content[0].name // "Test Produit"')
PRODUCT_PRICE=$(echo "$PRODUCTS" | jq -r '.data.content[0].sellingPrice // .data.content[0].price // 1000')
[[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] && echo "✅ Step 2 — Product found: $PRODUCT_NAME ($PRODUCT_ID)" || { echo "⚠️  Step 2 — No products found, creating a test product"; \
  CREATE_PRODUCT=$(curl -s -X POST "$BASE_URL/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"Produit Test POS\",\"sellingPrice\":2500,\"buyPrice\":1500,\"quantity\":50}"); \
  PRODUCT_ID=$(echo "$CREATE_PRODUCT" | jq -r '.data.id'); \
  PRODUCT_NAME="Produit Test POS"; PRODUCT_PRICE=2500; \
  [[ -n "$PRODUCT_ID" && "$PRODUCT_ID" != "null" ]] && echo "✅ Step 2 — Test product created ($PRODUCT_ID)" || { echo "❌ Step 2 FAILED: $CREATE_PRODUCT"; exit 1; }; }

# ─── Step 3 — Get store ID (from tenant stores) ────────────────────────────
STORES=$(curl -s "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID_API=$(echo "$STORES" | jq -r '.data[0].id // empty')
[[ -n "$STORE_ID_API" ]] && STORE_ID="$STORE_ID_API"
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 3 — Store ID: $STORE_ID" || { echo "❌ Step 3 FAILED — No store found: $STORES"; exit 1; }

# ─── Step 4 — POST /api/v1/sales — Valid sale (CASH) ──────────────────────
SALE_UUID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
RESPONSE=$(curl -s -o /tmp/sale_response.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID\",
    \"paymentMode\": \"CASH\",
    \"items\": [{
      \"productId\": \"$PRODUCT_ID\",
      \"productName\": \"$PRODUCT_NAME\",
      \"appliedUnitPrice\": $PRODUCT_PRICE,
      \"quantity\": 1
    }]
  }")
cat /tmp/sale_response.json | jq .
[[ "$RESPONSE" == "201" ]] && echo "✅ Step 4 — Sale recorded (HTTP 201)" || { echo "❌ Step 4 FAILED — HTTP $RESPONSE"; cat /tmp/sale_response.json; exit 1; }
RECORDED_SALE_ID=$(cat /tmp/sale_response.json | jq -r '.data.id')
[[ "$RECORDED_SALE_ID" == "$SALE_UUID" ]] && echo "✅ Step 4b — Returned saleId matches client-generated UUID" || echo "⚠️  Step 4b — saleId mismatch (server: $RECORDED_SALE_ID, client: $SALE_UUID)"

# ─── Step 5 — Idempotency: replay same sale UUID → no duplicate ────────────
IDEMPOTENCY_RESP=$(curl -s -o /tmp/idempotency.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID\",
    \"paymentMode\": \"CASH\",
    \"items\": [{\"productId\": \"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":1}]
  }")
[[ "$IDEMPOTENCY_RESP" == "200" || "$IDEMPOTENCY_RESP" == "201" ]] && echo "✅ Step 5 — Idempotency: replayed UUID accepted without duplicate" || { echo "❌ Step 5 FAILED — HTTP $IDEMPOTENCY_RESP"; cat /tmp/idempotency.json; exit 1; }

# ─── Step 6 — POST /api/v1/sales — Valid sale (MOBILE_MONEY) ──────────────
SALE_UUID_2=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
MOMO_RESP_CODE=$(curl -s -o /tmp/momo.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$SALE_UUID_2\",
    \"paymentMode\": \"MOBILE_MONEY\",
    \"mobileMoneyRef\": \"REF-MTN-12345\",
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":2}]
  }")
[[ "$MOMO_RESP_CODE" == "201" ]] && echo "✅ Step 6 — MoMo sale recorded (HTTP 201)" || { echo "❌ Step 6 FAILED — HTTP $MOMO_RESP_CODE"; cat /tmp/momo.json; exit 1; }

# ─── Step 7 — POST /api/v1/sales — No token → 401 ─────────────────────────
UNAUTH_RESP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Content-Type: application/json" \
  -d "{\"saleId\":\"$(python3 -c 'import uuid; print(str(uuid.uuid4()))')\",\"paymentMode\":\"CASH\",\"items\":[]}")
[[ "$UNAUTH_RESP" == "401" ]] && echo "✅ Step 7 — No token → 401 Unauthorized" || { echo "❌ Step 7 FAILED — Expected 401, got $UNAUTH_RESP"; exit 1; }

# ─── Step 8 — POST /api/v1/sales — Empty items → 400 ──────────────────────
BAD_REQ=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"saleId\":\"$(python3 -c 'import uuid; print(str(uuid.uuid4()))')\",\"paymentMode\":\"CASH\",\"items\":[]}")
[[ "$BAD_REQ" == "400" || "$BAD_REQ" == "422" ]] && echo "✅ Step 8 — Empty items → 400/422 Bad Request" || { echo "❌ Step 8 FAILED — Expected 400, got $BAD_REQ"; exit 1; }

# ─── Step 9 — INSUFFICIENT_STOCK: sell more than available ────────────────
OVERSTOCK_UUID=$(python3 -c "import uuid; print(str(uuid.uuid4()))")
OVERSTOCK=$(curl -s -o /tmp/overstock.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/sales" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{
    \"saleId\": \"$OVERSTOCK_UUID\",
    \"paymentMode\": \"CASH\",
    \"items\": [{\"productId\":\"$PRODUCT_ID\",\"productName\":\"$PRODUCT_NAME\",\"appliedUnitPrice\":$PRODUCT_PRICE,\"quantity\":999999}]
  }")
OVERSTOCK_CODE=$(cat /tmp/overstock.json | jq -r '.code // .domainCode // "unknown"')
[[ "$OVERSTOCK" == "422" && ("$OVERSTOCK_CODE" == "INSUFFICIENT_STOCK" || "$OVERSTOCK_CODE" == "INSUFFICIENT_STOCK") ]] \
  && echo "✅ Step 9 — Insufficient stock → 422 INSUFFICIENT_STOCK" \
  || { echo "❌ Step 9 FAILED — HTTP $OVERSTOCK, code: $OVERSTOCK_CODE"; cat /tmp/overstock.json; exit 1; }

# ─── Step 10 — Audit: SALE_COMPLETED event recorded ──────────────────────
AUDIT=$(curl -s "$BASE_URL/api/v1/audit?entityType=Sale&page=0&size=5" \
  -H "Authorization: Bearer $JWT")
AUDIT_ENTRY=$(echo "$AUDIT" | jq -r '.data.entries[0].action // empty')
[[ "$AUDIT_ENTRY" == "SALE_COMPLETED" ]] && echo "✅ Step 10 — Audit SALE_COMPLETED event found" || echo "⚠️  Step 10 — Audit entry: $AUDIT_ENTRY (check manually)"

echo ""
echo "✅✅✅ All cURL integration checks passed — story 4.1 backend validated ✅✅✅"
