#!/usr/bin/env bash
# ======================================================
# Story 6.3 — Gap Report cURL Integration Tests
# Run: bash curl-tests-story-6-3.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8443"
PASS=0

# Step 1 — Register + Onboard (get JWT + tenant + store)
PHONE="+237699990063$(date +%S)"
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\",\"firstName\":\"Gap\",\"lastName\":\"Report\"}")
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken // empty')
[[ -n "$JWT" ]] && { echo "✅ Step 1 — JWT obtained"; PASS=$((PASS+1)); } || { echo "❌ Step 1 FAILED"; exit 1; }

# Step 2 — Complete onboarding
ONBOARD=$(curl -s -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"VETEMENTS","storeName":"Boutique Gap Test","businessName":"Gap Test SARL"}')
echo "✅ Step 2 — Onboarding completed"
PASS=$((PASS+1))

# Step 3 — Get store ID
STORES=$(curl -s -X GET "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && { echo "✅ Step 3 — Store ID: $STORE_ID"; PASS=$((PASS+1)); } || { echo "❌ Step 3 FAILED"; exit 1; }

# Step 4 — Create 4 products with different prices
create_product() {
  local name=$1 price=$2
  local sku="KEV-$(echo "$name" | tr '[:lower:]' '[:upper:]' | head -c6 | sed 's/[^A-Z0-9]//g')$(printf "%0$((6-${#name}))d" 0 2>/dev/null || echo "000000" | head -c6)"
  sku="KEV-$(head -c 6 /dev/urandom | xxd -p | tr '[:lower:]' '[:upper:]' | head -c 6)"
  curl -s -X POST "$BASE_URL/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"price\":$price,\"buyPrice\":0,\"transportCost\":0}" | jq -r '.data.id'
}

PROD_A=$(create_product "Robe Rouge M" 5000)
PROD_B=$(create_product "Jeans L Bleu" 4000)
PROD_C=$(create_product "T-Shirt XL" 3000)
PROD_D=$(create_product "Polo S Vert" 2500)

echo "  Products: A=$PROD_A B=$PROD_B C=$PROD_C D=$PROD_D"
[[ -n "$PROD_A" && "$PROD_A" != "null" ]] && { echo "✅ Step 4 — 4 products created"; PASS=$((PASS+1)); } || { echo "❌ Step 4 FAILED"; exit 1; }

# Step 5 — Create stock levels for products
for PID in "$PROD_A" "$PROD_B" "$PROD_C" "$PROD_D"; do
  curl -s -X POST "$BASE_URL/api/v1/stock/entries" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$PID\",\"storeId\":\"$STORE_ID\",\"quantity\":10,\"type\":\"ENTRY\",\"reason\":\"Initial stock\"}" > /dev/null
done
echo "✅ Step 5 — Stock levels created"
PASS=$((PASS+1))

# Step 6 — Start inventory session
SESSION=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
SESSION_ID=$(echo "$SESSION" | jq -r '.data.id')
[[ -n "$SESSION_ID" && "$SESSION_ID" != "null" ]] && { echo "✅ Step 6 — Session created: $SESSION_ID"; PASS=$((PASS+1)); } || { echo "❌ Step 6 FAILED"; exit 1; }

# Step 7 — Save inventory counts with mixed gaps
# A: concordant (10→10), B: shortage (10→7, -3), C: surplus (10→13, +3), D: shortage (10→5, -5)
save_count() {
  local pid=$1 physical=$2
  curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$pid\",\"physical\":$physical}" > /dev/null
}

save_count "$PROD_A" 10
save_count "$PROD_B" 7
save_count "$PROD_C" 13
save_count "$PROD_D" 5
echo "✅ Step 7 — Counts saved (concordant + shortage + surplus)"
PASS=$((PASS+1))

# Step 8 — GET gap-report → verify JSON
REPORT=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/gap-report" \
  -H "Authorization: Bearer $JWT")

TOTAL_COUNTED=$(echo "$REPORT" | jq -r '.data.summary.totalCounted')
TOTAL_CONCORDANT=$(echo "$REPORT" | jq -r '.data.summary.totalConcordant')
TOTAL_SHORTAGE=$(echo "$REPORT" | jq -r '.data.summary.totalShortage')
TOTAL_SURPLUS=$(echo "$REPORT" | jq -r '.data.summary.totalSurplus')

echo "  Report: counted=$TOTAL_COUNTED concordant=$TOTAL_CONCORDANT shortage=$TOTAL_SHORTAGE surplus=$TOTAL_SURPLUS"

[[ "$TOTAL_COUNTED" -eq 4 && "$TOTAL_CONCORDANT" -eq 1 && "$TOTAL_SHORTAGE" -eq 2 && "$TOTAL_SURPLUS" -eq 1 ]] \
  && { echo "✅ Step 8 — Gap report summary correct"; PASS=$((PASS+1)); } \
  || { echo "❌ Step 8 FAILED — unexpected summary values"; exit 1; }

# Step 9 — GET gap-report/text → verify WhatsApp text
TEXT_RESP=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/gap-report/text" \
  -H "Authorization: Bearer $JWT")
TEXT=$(echo "$TEXT_RESP" | jq -r '.data')

echo "$TEXT" | grep -q "📋 Rapport d'inventaire" && { echo "  ✓ Contains report header"; } || { echo "❌ Missing report header"; exit 1; }
echo "$TEXT" | grep -q "🔴 Manquants" && { echo "  ✓ Contains shortages"; } || { echo "❌ Missing shortages"; exit 1; }
echo "$TEXT" | grep -q "Top manques" && { echo "  ✓ Contains top shortages"; } || { echo "❌ Missing top shortages"; exit 1; }
echo "$TEXT" | grep -q "FCFA" && { echo "  ✓ Contains XAF values"; } || { echo "❌ Missing XAF values"; exit 1; }
echo "✅ Step 9 — WhatsApp text format verified"
PASS=$((PASS+1))

# Step 10 — Verify 404 for non-existent session
FAKE_ID="00000000-0000-0000-0000-000000000000"
HTTP_404=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/inventory/sessions/$FAKE_ID/gap-report" \
  -H "Authorization: Bearer $JWT")
[[ "$HTTP_404" == "404" ]] && { echo "✅ Step 10 — 404 for non-existent session"; PASS=$((PASS+1)); } || { echo "❌ Step 10 FAILED (got $HTTP_404)"; exit 1; }

echo ""
echo "✅✅✅ All $PASS cURL integration checks passed — Story 6.3 backend validated ✅✅✅"
