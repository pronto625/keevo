#!/bin/bash

set -e

echo "=== Test API Tenant Preferences ==="

# 1. Register a new user
echo "1. Registering user..."
REGISTER_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "phoneNumber": "+23712345988",
    "password": "TestPass123!"
  }')

echo "Register response: $REGISTER_RESPONSE"

TOKEN=$(echo "$REGISTER_RESPONSE" | grep -o '"token":"[^"]*"' | cut -d'"' -f4)
if [ -z "$TOKEN" ]; then
  echo "❌ Failed to get token"
  exit 1
fi

echo "✅ Got token: ${TOKEN:0:50}..."

# 2. Complete onboarding (CLOTHING sector)
echo "2. Completing onboarding..."
ONBOARDING_RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/onboarding/complete \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sectorType": "CLOTHING", 
    "storeName": "Test Boutique"
  }')

echo "Onboarding response: $ONBOARDING_RESPONSE"

# 3. Get tenant preferences
echo "3. Getting tenant preferences..."
PREFERENCES_RESPONSE=$(curl -s -X GET http://localhost:8080/api/v1/tenant/preferences \
  -H "Authorization: Bearer $TOKEN")

echo "Preferences response: $PREFERENCES_RESPONSE"

# 4. Get categories (should have CLOTHING sector categories)  
echo "4. Getting categories..."
CATEGORIES_RESPONSE=$(curl -s -X GET http://localhost:8080/api/v1/categories \
  -H "Authorization: Bearer $TOKEN")

echo "Categories response: $CATEGORIES_RESPONSE"

# Simple count check - count commas in the categories array + 1
CATEGORY_COUNT=$(echo "$CATEGORIES_RESPONSE" | grep -o '"name":' | wc -l)
echo "📊 Found $CATEGORY_COUNT categories"

if [ "$CATEGORY_COUNT" -eq 13 ]; then
  echo "✅ Expected 13 categories for CLOTHING sector"
else
  echo "❌ Expected 13 categories, got $CATEGORY_COUNT"
fi

echo "=== Test completed ==="