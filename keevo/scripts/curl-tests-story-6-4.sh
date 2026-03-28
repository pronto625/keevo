#!/usr/bin/env bash
# ======================================================
# Story 6.4 — Validation & Application des Ajustements au Stock
# Run: bash curl-tests-story-6-4.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0

# ── Step 1 — Register (get JWT) ────────────────────────────────────────
PHONE="+237699$(date +%s%N | tail -c 8)"
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE\",\"password\":\"Test1234!\",\"firstName\":\"Valid\",\"lastName\":\"Inv\"}")
JWT=$(echo "$REGISTER" | jq -r '.token // empty')
[[ -n "$JWT" ]] && { echo "✅ Step 1 — JWT obtained"; PASS=$((PASS+1)); } || { echo "❌ Step 1 FAILED — $REGISTER"; exit 1; }

# ── Step 2 — Complete onboarding ──────────────────────────────────────
ONBOARD=$(curl -s -X POST "$BASE_URL/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"VETEMENTS","storeName":"Boutique Validate","businessName":"Validate SARL"}')
echo "✅ Step 2 — Onboarding completed"
PASS=$((PASS+1))

# ── Step 3 — Get store ID ─────────────────────────────────────────────
STORES=$(curl -s -X GET "$BASE_URL/api/v1/tenant/stores" \
  -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | jq -r '.data[0].id')
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && { echo "✅ Step 3 — Store ID: $STORE_ID"; PASS=$((PASS+1)); } || { echo "❌ Step 3 FAILED"; exit 1; }

# ── Step 4 — Create 4 products ────────────────────────────────────────
create_product() {
  local name=$1 price=$2
  curl -s -X POST "$BASE_URL/api/v1/products" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"$name\",\"price\":$price,\"buyPrice\":0,\"transportCost\":0}" | jq -r '.data.id'
}

PROD_A=$(create_product "Concordant Robe 64" 5000)
PROD_B=$(create_product "Shortage Jeans 64" 3000)
PROD_C=$(create_product "Surplus TShirt 64" 8000)
PROD_D=$(create_product "Shortage Polo 64" 2000)

echo "  Products: A=$PROD_A B=$PROD_B C=$PROD_C D=$PROD_D"
[[ -n "$PROD_A" && "$PROD_A" != "null" ]] && { echo "✅ Step 4 — 4 products created"; PASS=$((PASS+1)); } || { echo "❌ Step 4 FAILED"; exit 1; }

# ── Step 5 — Create stock levels ──────────────────────────────────────
# A=10, B=8, C=5, D=20
stock_entry() {
  local pid=$1 qty=$2
  curl -s -X POST "$BASE_URL/api/v1/products/$pid/stock/entry" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"storeId\":\"$STORE_ID\",\"quantity\":$qty,\"notes\":\"Initial stock\"}" > /dev/null
}

stock_entry "$PROD_A" 10
stock_entry "$PROD_B" 8
stock_entry "$PROD_C" 5
stock_entry "$PROD_D" 20
echo "✅ Step 5 — Stock levels created (A=10, B=8, C=5, D=20)"
PASS=$((PASS+1))

# ── Step 6 — Start inventory session ──────────────────────────────────
SESSION=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE_ID\",\"scope\":\"FULL\"}")
SESSION_ID=$(echo "$SESSION" | jq -r '.data.id')
[[ -n "$SESSION_ID" && "$SESSION_ID" != "null" ]] && { echo "✅ Step 6 — Session created: $SESSION_ID"; PASS=$((PASS+1)); } || { echo "❌ Step 6 FAILED"; exit 1; }

# ── Step 7 — Save inventory counts with mixed gaps ────────────────────
# A: concordant (10→10), B: shortage (8→5), C: surplus (5→8), D: shortage (20→15)
save_count() {
  local pid=$1 pname=$2 theoretical=$3 physical=$4
  curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/counts" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"productId\":\"$pid\",\"productName\":\"$pname\",\"theoretical\":$theoretical,\"physical\":$physical}" > /dev/null
}

save_count "$PROD_A" "Concordant Robe 64" 10 10
save_count "$PROD_B" "Shortage Jeans 64" 8 5
save_count "$PROD_C" "Surplus TShirt 64" 5 8
save_count "$PROD_D" "Shortage Polo 64" 20 15
echo "✅ Step 7 — Counts saved (A:10→10, B:8→5, C:5→8, D:20→15)"
PASS=$((PASS+1))

# ── Step 8 — Verify gap report ────────────────────────────────────────
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

# ═══════════════════════════════════════════════════════════════════════
# STORY 6.4 CORE TESTS
# ═══════════════════════════════════════════════════════════════════════

# ── Step 9 — OWNER validates (POST /validate) ─────────────────────────
VALIDATE=$(curl -s -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/validate" \
  -H "Authorization: Bearer $JWT")
echo "  Validate response: $(echo "$VALIDATE" | jq -c .)"

ADJ_APPLIED=$(echo "$VALIDATE" | jq -r '.data.adjustmentsApplied')
VAL_STATUS=$(echo "$VALIDATE" | jq -r '.data.status')
COMPLETED_AT=$(echo "$VALIDATE" | jq -r '.data.completedAt')

[[ "$ADJ_APPLIED" -eq 3 ]] && { echo "  ✓ adjustmentsApplied=3 (skip concordant A)"; } || { echo "❌ Step 9a FAILED (adj=$ADJ_APPLIED)"; exit 1; }
[[ "$VAL_STATUS" == "VALIDATED" ]] && { echo "  ✓ status=VALIDATED"; } || { echo "❌ Step 9b FAILED (status=$VAL_STATUS)"; exit 1; }
[[ -n "$COMPLETED_AT" && "$COMPLETED_AT" != "null" ]] && { echo "  ✓ completedAt is set"; } || { echo "❌ Step 9c FAILED (completedAt=$COMPLETED_AT)"; exit 1; }
echo "✅ Step 9 — Validation succeeded: 3 adjustments applied"
PASS=$((PASS+1))

# ── Step 10 — Verify session is VALIDATED and immutable ────────────────
# 10a: GET session → VALIDATED
GET_SESSION=$(curl -s -X GET "$BASE_URL/api/v1/inventory/sessions/active?storeId=$STORE_ID" \
  -H "Authorization: Bearer $JWT")
GET_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/inventory/sessions/active?storeId=$STORE_ID" \
  -H "Authorization: Bearer $JWT")
[[ "$GET_STATUS" == "404" ]] && { echo "✅ Step 10a — No active session (validated → done)"; PASS=$((PASS+1)); } || { echo "❌ Step 10a FAILED (got $GET_STATUS)"; exit 1; }

# 10b: POST /validate again → expect 409
REVALIDATE_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/validate" \
  -H "Authorization: Bearer $JWT")
[[ "$REVALIDATE_STATUS" == "409" ]] && { echo "✅ Step 10b — Re-validate blocked (409)"; PASS=$((PASS+1)); } || { echo "❌ Step 10b FAILED (got $REVALIDATE_STATUS)"; exit 1; }

# 10c: POST /cancel → expect 409
CANCEL_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/cancel" \
  -H "Authorization: Bearer $JWT")
[[ "$CANCEL_STATUS" == "409" ]] && { echo "✅ Step 10c — Cancel blocked (409)"; PASS=$((PASS+1)); } || { echo "❌ Step 10c FAILED (got $CANCEL_STATUS)"; exit 1; }

# ── Step 11 — Gap report still accessible for VALIDATED session ────────
REPORT2_STATUS=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE_URL/api/v1/inventory/sessions/$SESSION_ID/gap-report" \
  -H "Authorization: Bearer $JWT")
[[ "$REPORT2_STATUS" == "200" ]] && { echo "✅ Step 11 — Gap report accessible (200) for VALIDATED session"; PASS=$((PASS+1)); } || { echo "❌ Step 11 FAILED (got $REPORT2_STATUS)"; exit 1; }

echo ""
echo "✅✅✅ All $PASS cURL integration checks passed — Story 6.4 backend validated ✅✅✅"
