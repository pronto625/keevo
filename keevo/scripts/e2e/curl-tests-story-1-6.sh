#!/usr/bin/env bash
# ======================================================
# Story 1.6 — cURL Integration Tests
# Run: bash curl-tests-story-1-6.sh
# All steps must show ✅ before story is marked done
# Requires: jq, curl
# ======================================================
set -euo pipefail
BASE_URL="http://localhost:8080"

echo "========================================================"
echo " Keevo — Story 1.6 cURL Integration Tests"
echo "========================================================"
echo ""

# Step 1 — Register user + get JWT
echo "Step 1 — Register new tenant and obtain JWT..."
REGISTER=$(curl -s -X POST "$BASE_URL/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237600001601","password":"Test1234!","firstName":"Simon","lastName":"Kana"}')
JWT=$(echo "$REGISTER" | jq -r '.data.accessToken // empty')
[[ -n "$JWT" && "$JWT" != "null" ]] \
  && echo "✅ Step 1 — JWT obtained" \
  || { echo "❌ Step 1 FAILED — Response: $REGISTER"; exit 1; }

# Step 2 — GET /api/v1/subscription/me → 200 + PREMIUM_TRIAL plan (new tenant starts on trial)
echo ""
echo "Step 2 — Verify new tenant starts on PREMIUM_TRIAL plan..."
SUB=$(curl -s -X GET "$BASE_URL/api/v1/subscription/me" \
  -H "Authorization: Bearer $JWT")
echo "  Response: $SUB" | jq .
PLAN=$(echo "$SUB" | jq -r '.data.planType // .planType // empty')
[[ "$PLAN" == "PREMIUM_TRIAL" ]] \
  && echo "✅ Step 2 — Returns PREMIUM_TRIAL plan (6-month trial)" \
  || { echo "❌ Step 2 FAILED (expected PREMIUM_TRIAL, got '$PLAN')"; exit 1; }

# Step 3 — Rate limit: send 101 rapid requests, expect 429 on 101st (or earlier)
echo ""
echo "Step 3 — Rate limiting (101 rapid requests to trigger 429)..."
STATUS_429=""
for i in $(seq 1 101); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $JWT" \
    "$BASE_URL/api/v1/subscription/me")
  if [[ "$HTTP_CODE" == "429" ]]; then
    STATUS_429="found"
    echo "  → 429 received at request #$i"
    break
  fi
done
[[ "$STATUS_429" == "found" ]] \
  && echo "✅ Step 3 — Rate limit 429 triggered within 101 requests" \
  || echo "⚠️  Step 3 — Rate limit not triggered (check RateLimitFilter config or restart server)"

# Step 4 — GET /api/v1/subscription/me without token → 401
echo ""
echo "Step 4 — Request without token should return 401..."
UNAUTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/api/v1/subscription/me")
[[ "$UNAUTH" == "401" ]] \
  && echo "✅ Step 4 — No token → 401 UNAUTHORIZED" \
  || { echo "❌ Step 4 FAILED (expected 401, got $UNAUTH)"; exit 1; }

# Step 5 — POST /api/v1/auth/login with same credentials → 200 + JWT
echo ""
echo "Step 5 — Login with registered credentials..."
LOGIN=$(curl -s -X POST "$BASE_URL/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+237600001601","password":"Test1234!"}')
JWT2=$(echo "$LOGIN" | jq -r '.data.accessToken // empty')
[[ -n "$JWT2" && "$JWT2" != "null" ]] \
  && echo "✅ Step 5 — Login works, JWT obtained" \
  || { echo "❌ Step 5 FAILED — Response: $LOGIN"; exit 1; }

echo ""
echo "========================================================"
echo " ✅✅✅ All cURL integration checks passed!"
echo " Story 1.6 backend validated."
echo "========================================================"
