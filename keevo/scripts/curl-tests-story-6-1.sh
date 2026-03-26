#!/usr/bin/env bash
# ======================================================
# Story 6.1 — Inventory Session cURL Integration Tests
# Run: bash curl-tests-story-6-1.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"

# Step 1 — Register + Onboard (get JWT + tenant + store)
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"+237699990061","password":"Test1234!","firstName":"Inv","lastName":"Test"}')
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken // empty')
[[ -n "$JWT" ]] && echo "✅ Step 1 — JWT obtained" || { echo "❌ Step 1 FAILED"; exit 1; }

# Step 2 — Complete onboarding to get a store
ONBOARD=$(curl -s -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"VETEMENTS","storeName":"Boutique Inventaire","businessName":"Inv Test SARL"}')
echo "$ONBOARD" | jq .
echo "✅ Step 2 — Onboarding completed"

# Step 3 — Get store ID
STORES=$(curl -s -X GET "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 3 — Store ID: $STORE_ID" || { echo "❌ Step 3 FAILED"; exit 1; }

# Step 4 — Create FULL inventory session
SESSION=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
echo "$SESSION" | jq .
SESSION_ID=$(echo "$SESSION" | jq -r '.data.id')
[[ -n "$SESSION_ID" && "$SESSION_ID" != "null" ]] && echo "✅ Step 4 — Session created: $SESSION_ID" || { echo "❌ Step 4 FAILED"; exit 1; }

# Step 5 — Attempt duplicate session (same store) → expect 409
DUP=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
[[ "$DUP" == "409" ]] && echo "✅ Step 5 — Duplicate blocked (409)" || { echo "❌ Step 5 FAILED (got $DUP)"; exit 1; }

# Step 6 — Get active session
ACTIVE=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/active?storeId=$STORE_ID" \
  -H "Authorization: Bearer $JWT")
ACTIVE_STATUS=$(echo "$ACTIVE" | jq -r '.data.status')
[[ "$ACTIVE_STATUS" == "IN_PROGRESS" ]] && echo "✅ Step 6 — Active session is IN_PROGRESS" || { echo "❌ Step 6 FAILED"; exit 1; }

# Step 7 — Cancel session
CANCEL=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/cancel" \
  -H "Authorization: Bearer $JWT")
CANCEL_STATUS=$(echo "$CANCEL" | jq -r '.data.status')
[[ "$CANCEL_STATUS" == "CANCELLED" ]] && echo "✅ Step 7 — Session cancelled" || { echo "❌ Step 7 FAILED"; exit 1; }

# Step 8 — No active session after cancel → expect 404
NO_ACTIVE=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/inventory/sessions/active?storeId=$STORE_ID" \
  -H "Authorization: Bearer $JWT")
[[ "$NO_ACTIVE" == "404" ]] && echo "✅ Step 8 — No active session (404)" || { echo "❌ Step 8 FAILED (got $NO_ACTIVE)"; exit 1; }

# Step 9 — Create new session after cancel (should succeed)
SESSION2=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
SESSION2_ID=$(echo "$SESSION2" | jq -r '.data.id')
[[ -n "$SESSION2_ID" && "$SESSION2_ID" != "null" ]] && echo "✅ Step 9 — New session after cancel: $SESSION2_ID" || { echo "❌ Step 9 FAILED"; exit 1; }

# Step 10 — List sessions (should have 2: 1 cancelled + 1 in progress)
LIST=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions?page=0&size=10" \
  -H "Authorization: Bearer $JWT")
COUNT=$(echo "$LIST" | jq '.data | length')
[[ "$COUNT" -ge "2" ]] && echo "✅ Step 10 — List has $COUNT sessions" || { echo "❌ Step 10 FAILED (count=$COUNT)"; exit 1; }

# Step 11 — Cancel without auth → 401
NO_AUTH=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION2_ID/cancel")
[[ "$NO_AUTH" == "401" ]] && echo "✅ Step 11 — Unauthorized cancel (401)" || { echo "❌ Step 11 FAILED (got $NO_AUTH)"; exit 1; }

# Step 12 — Create PARTIAL session with categoryIds
# First cancel the active one
curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION2_ID/cancel" \
  -H "Authorization: Bearer $JWT" > /dev/null

# Get a category ID
CATS=$(curl -s -X GET "$BASE_URL/api/v1/categories" \
  -H "Authorization: Bearer $JWT")
CAT_ID=$(echo "$CATS" | jq -r '.data[0].id // empty')
if [[ -n "$CAT_ID" ]]; then
  PARTIAL=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"PARTIAL\",\"categoryIds\":[\"$CAT_ID\"]}")
  PARTIAL_SCOPE=$(echo "$PARTIAL" | jq -r '.data.scope')
  [[ "$PARTIAL_SCOPE" == "PARTIAL" ]] && echo "✅ Step 12 — Partial session created" || { echo "❌ Step 12 FAILED"; exit 1; }
else
  echo "⚠️  Step 12 — Skipped (no categories found)"
fi

echo ""
echo "✅✅✅ All cURL integration checks passed — Story 6.1 backend validated ✅✅✅"
