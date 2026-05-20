#!/usr/bin/env bash
# curl-tests-story-8-6.sh
# Story 8.6 — Changement de mot de passe volontaire & profil utilisateur
# Tests: AC1-AC8

BASE="http://localhost:4500"
PASS=0
FAIL=0

GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

ok()  { echo -e "${GREEN}[PASS]${NC} $1"; PASS=$((PASS+1)); }
fail(){ echo -e "${RED}[FAIL]${NC} $1 — $2"; FAIL=$((FAIL+1)); }

# Unique suffix per run to avoid phone conflicts on re-runs
SUFFIX=$(date +%s | tail -c 5)
PHONE_OWNER="+2376908${SUFFIX}"
PHONE_EMP="+2376909${SUFFIX}"

# ─── Helpers ─────────────────────────────────────────────────────────────────

# register_and_login <phone> <pwd> → returns full select-tenant response JSON
register_and_login() {
  local phone="$1" pwd="$2"
  curl -s -X POST "$BASE/api/v1/auth/register" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$phone\",\"password\":\"$pwd\"}" > /dev/null

  local loginResp loginToken tenantCode
  loginResp=$(curl -s -X POST "$BASE/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$phone\",\"password\":\"$pwd\"}")
  loginToken=$(echo "$loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null)
  tenantCode=$(echo "$loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); m=d.get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null)

  curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginToken\":\"$loginToken\",\"tenantCode\":\"$tenantCode\"}"
}

# login_existing <phone> <pwd> → returns select-tenant response JSON (no register)
login_existing() {
  local phone="$1" pwd="$2"
  local loginResp loginToken tenantCode
  loginResp=$(curl -s -X POST "$BASE/api/v1/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"$phone\",\"password\":\"$pwd\"}")
  loginToken=$(echo "$loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null)
  tenantCode=$(echo "$loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); m=d.get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null)
  if [[ -z "$loginToken" || "$loginToken" == "None" ]]; then
    echo "$loginResp"  # return error
    return
  fi
  curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
    -H "Content-Type: application/json" \
    -d "{\"loginToken\":\"$loginToken\",\"tenantCode\":\"$tenantCode\"}"
}

get_access() {
  echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('accessToken',''))" 2>/dev/null
}

get_refresh() {
  echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('refreshToken',''))" 2>/dev/null
}

jget() { echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('$2',''))" 2>/dev/null; }

echo ""
echo "════════════════════════════════════════════════════════════════"
echo " Story 8.6 — Curl E2E Tests  [run suffix: $SUFFIX]"
echo "════════════════════════════════════════════════════════════════"

# ─── Step 1: Register & login as OWNER ───────────────────────────────────────
echo ""
echo "── Step 1: Register OWNER ($PHONE_OWNER) ──────────────────────"

PWD_OWNER="Owner@1234"
tokens=$(register_and_login "$PHONE_OWNER" "$PWD_OWNER")
TOKEN_OWNER=$(get_access "$tokens")

if [[ -n "$TOKEN_OWNER" && "$TOKEN_OWNER" != "None" ]]; then
  ok "Step 1: OWNER login — access token obtained"
else
  fail "Step 1" "Could not get OWNER access token — tokens: $tokens"
fi

# ─── Step 2: GET /api/v1/auth/profile — OWNER (AC3, AC4) ─────────────────────
echo ""
echo "── Step 2: GET /api/v1/auth/profile (OWNER — AC3, AC4) ─────────"

profile_resp=$(curl -s -X GET "$BASE/api/v1/auth/profile" \
  -H "Authorization: Bearer $TOKEN_OWNER")

role=$(jget "$profile_resp" "role")
phone=$(jget "$profile_resp" "phoneNumber")
fn=$(echo "$profile_resp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('firstName','NOT_NULL'))" 2>/dev/null)
sid=$(echo "$profile_resp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('storeId','NOT_NULL'))" 2>/dev/null)

[[ "$role" == "OWNER" ]] && ok "Step 2a: OWNER role in profile" || fail "Step 2a" "role=$role resp=$profile_resp"
[[ "$phone" == "$PHONE_OWNER" ]] && ok "Step 2b: OWNER phoneNumber correct" || fail "Step 2b" "phone=$phone"
[[ "$fn" == "None" ]] && ok "Step 2c: OWNER firstName is null" || fail "Step 2c" "firstName=$fn"
[[ "$sid" == "None" ]] && ok "Step 2d: OWNER storeId is null" || fail "Step 2d" "storeId=$sid"

# ─── Step 3: GET /profile — 401 without token (AC3) ─────────────────────────
echo ""
echo "── Step 3: GET /profile — 401 without token (AC3) ─────────────"

status=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/auth/profile")
[[ "$status" == "401" ]] && ok "Step 3: 401 for unauthenticated request" || fail "Step 3" "http=$status"

# ─── Step 4: POST /change-password — OWNER success (AC7) ─────────────────────
echo ""
echo "── Step 4: POST /change-password — OWNER success (AC7) ─────────"

NEW_PWD="NewOwner@5678"
chg_resp=$(curl -s -X POST "$BASE/api/v1/auth/change-password" \
  -H "Authorization: Bearer $TOKEN_OWNER" \
  -H "Content-Type: application/json" \
  -d "{\"currentPassword\":\"$PWD_OWNER\",\"newPassword\":\"$NEW_PWD\"}")

new_access=$(get_access "$chg_resp")
new_role=$(jget "$chg_resp" "role")
new_storeId=$(echo "$chg_resp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('storeId','NOT_NULL'))" 2>/dev/null)

[[ -n "$new_access" && "$new_access" != "None" ]] && ok "Step 4a: OWNER change-password — new access token returned" || fail "Step 4a" "no access token — resp: $chg_resp"
[[ "$new_role" == "OWNER" ]] && ok "Step 4b: OWNER change-password — role=OWNER in response" || fail "Step 4b" "role=$new_role"
[[ "$new_storeId" == "None" ]] && ok "Step 4c: OWNER change-password — storeId=null" || fail "Step 4c" "storeId=$new_storeId"

TOKEN_OWNER="$new_access"

# ─── Step 5: OWNER re-login with new password ─────────────────────────────────
echo ""
echo "── Step 5: OWNER re-login with new password ─────────────────────"

tokens2=$(login_existing "$PHONE_OWNER" "$NEW_PWD")
TOKEN_OWNER2=$(get_access "$tokens2")
[[ -n "$TOKEN_OWNER2" && "$TOKEN_OWNER2" != "None" ]] && ok "Step 5: OWNER can login with new password" || fail "Step 5" "Cannot login with new pwd — $tokens2"

# ─── Step 6: OWNER cannot login with old password ─────────────────────────────
echo ""
echo "── Step 6: OWNER cannot login with old password ─────────────────"

old_loginResp=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_OWNER\",\"password\":\"$PWD_OWNER\"}")
old_loginToken=$(echo "$old_loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null)
[[ -z "$old_loginToken" || "$old_loginToken" == "None" ]] && ok "Step 6: Old password rejected" || fail "Step 6" "Old password still works!"

# ─── Step 7: Create EMPLOYEE (via OWNER) ──────────────────────────────────────
echo ""
echo "── Step 7: Create EMPLOYEE via OWNER ────────────────────────────"

# Stores are wrapped: {"data": [...], "timestamp": ...}
stores_resp=$(curl -s "$BASE/api/v1/stores" -H "Authorization: Bearer $TOKEN_OWNER2")
store_id=$(echo "$stores_resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
l = d.get('data', d) if isinstance(d, dict) else d
if isinstance(l, list) and l:
    print(l[0]['id'])
else:
    print('')
" 2>/dev/null)

if [[ -z "$store_id" || "$store_id" == "None" ]]; then
  store_resp=$(curl -s -X POST "$BASE/api/v1/stores" \
    -H "Authorization: Bearer $TOKEN_OWNER2" \
    -H "Content-Type: application/json" \
    -d '{"name":"Boutique 8.6","type":"STORE"}')
  store_id=$(echo "$store_resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
data = d.get('data', d)
if isinstance(data, dict):
    print(data.get('id', ''))
else:
    print('')
" 2>/dev/null)
fi

emp_resp=$(curl -s -X POST "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $TOKEN_OWNER2" \
  -H "Content-Type: application/json" \
  -d "{\"firstName\":\"Jean\",\"lastName\":\"Dupont\",\"phoneNumber\":\"$PHONE_EMP\",\"storeId\":\"$store_id\"}")

# Employee response: {"data": {"employee": {...}, "temporaryPassword": "..."}, "timestamp": ...}
emp_pwd=$(echo "$emp_resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
data = d.get('data', d)
print(data.get('temporaryPassword', data.get('generatedPassword', '')))
" 2>/dev/null)
emp_id=$(echo "$emp_resp" | python3 -c "
import json,sys
d=json.load(sys.stdin)
data = d.get('data', d)
emp = data.get('employee', data)
print(emp.get('id', ''))
" 2>/dev/null)

if [[ -n "$emp_pwd" && "$emp_pwd" != "None" ]]; then
  ok "Step 7: EMPLOYEE created (id=$emp_id, storeId=$store_id)"
else
  fail "Step 7" "Could not create employee — resp: $emp_resp"
  emp_pwd="Employee@1234"
fi

# ─── Step 8: EMPLOYEE login ───────────────────────────────────────────────────
echo ""
echo "── Step 8: EMPLOYEE login ────────────────────────────────────────"

emp_loginResp=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_EMP\",\"password\":\"$emp_pwd\"}")

emp_loginToken=$(echo "$emp_loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('loginToken',''))" 2>/dev/null)
emp_tenantCode=$(echo "$emp_loginResp" | python3 -c "import json,sys; d=json.load(sys.stdin); m=d.get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null)

emp_tokens=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$emp_loginToken\",\"tenantCode\":\"$emp_tenantCode\"}")

emp_req_change=$(echo "$emp_tokens" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('passwordChangeRequired',False))" 2>/dev/null)
TOKEN_EMP_FIRST=$(get_access "$emp_tokens")

[[ -n "$TOKEN_EMP_FIRST" && "$TOKEN_EMP_FIRST" != "None" ]] && ok "Step 8: EMPLOYEE first login — token obtained" || fail "Step 8" "resp: $emp_tokens (loginToken=$emp_loginToken tenantCode=$emp_tenantCode)"

# EMPLOYEE must set mandatory password first (passwordChangeRequired=True)
if [[ "$emp_req_change" == "True" ]]; then
  NEW_EMP_PWD="Employe@7890"
  emp_chg=$(curl -s -X POST "$BASE/api/v1/auth/change-password" \
    -H "Authorization: Bearer $TOKEN_EMP_FIRST" \
    -H "Content-Type: application/json" \
    -d "{\"currentPassword\":\"$emp_pwd\",\"newPassword\":\"$NEW_EMP_PWD\"}")
  TOKEN_EMP=$(get_access "$emp_chg")
  [[ -n "$TOKEN_EMP" && "$TOKEN_EMP" != "None" ]] && ok "Step 8b: EMPLOYEE mandatory password set — token obtained" || fail "Step 8b" "resp: $emp_chg"
  emp_pwd="$NEW_EMP_PWD"
else
  TOKEN_EMP="$TOKEN_EMP_FIRST"
fi

# ─── Step 9: GET /profile — EMPLOYEE (AC4) ────────────────────────────────────
echo ""
echo "── Step 9: GET /profile — EMPLOYEE (AC4) ────────────────────────"

emp_profile=$(curl -s -X GET "$BASE/api/v1/auth/profile" \
  -H "Authorization: Bearer $TOKEN_EMP")

emp_role=$(jget "$emp_profile" "role")
emp_fn=$(jget "$emp_profile" "firstName")
emp_ln=$(jget "$emp_profile" "lastName")
emp_sid=$(echo "$emp_profile" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('storeId',''))" 2>/dev/null)
emp_sname=$(echo "$emp_profile" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('storeName',''))" 2>/dev/null)

[[ "$emp_role" == "EMPLOYEE" ]] && ok "Step 9a: EMPLOYEE role in profile" || fail "Step 9a" "role=$emp_role resp=$emp_profile"
[[ "$emp_fn" == "Jean" ]] && ok "Step 9b: EMPLOYEE firstName=Jean" || fail "Step 9b" "fn=$emp_fn"
[[ "$emp_ln" == "Dupont" ]] && ok "Step 9c: EMPLOYEE lastName=Dupont" || fail "Step 9c" "ln=$emp_ln"
[[ -n "$emp_sid" && "$emp_sid" != "None" ]] && ok "Step 9d: EMPLOYEE storeId populated" || fail "Step 9d" "storeId=$emp_sid"
[[ -n "$emp_sname" && "$emp_sname" != "None" ]] && ok "Step 9e: EMPLOYEE storeName populated" || fail "Step 9e" "storeName=$emp_sname"

# ─── Step 10: EMPLOYEE voluntary change-password (AC7) ────────────────────────
echo ""
echo "── Step 10: EMPLOYEE voluntary change-password (AC7) ───────────"

EMP_NEW_PWD="Employe@NewPass1"
emp_chg2=$(curl -s -X POST "$BASE/api/v1/auth/change-password" \
  -H "Authorization: Bearer $TOKEN_EMP" \
  -H "Content-Type: application/json" \
  -d "{\"currentPassword\":\"$emp_pwd\",\"newPassword\":\"$EMP_NEW_PWD\"}")

emp_new_access=$(get_access "$emp_chg2")
emp_new_role=$(jget "$emp_chg2" "role")
emp_new_storeId=$(echo "$emp_chg2" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('storeId',''))" 2>/dev/null)

[[ -n "$emp_new_access" && "$emp_new_access" != "None" ]] && ok "Step 10a: EMPLOYEE change-password — new access token" || fail "Step 10a" "resp: $emp_chg2"
[[ "$emp_new_role" == "EMPLOYEE" ]] && ok "Step 10b: EMPLOYEE change-password — role=EMPLOYEE" || fail "Step 10b" "role=$emp_new_role"
[[ -n "$emp_new_storeId" && "$emp_new_storeId" != "None" ]] && ok "Step 10c: EMPLOYEE change-password — storeId preserved" || fail "Step 10c" "storeId=$emp_new_storeId"

# ─── Step 11: Wrong current password → error ──────────────────────────────────
echo ""
echo "── Step 11: Wrong current password → error ──────────────────────"

wrong_status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE/api/v1/auth/change-password" \
  -H "Authorization: Bearer $TOKEN_OWNER2" \
  -H "Content-Type: application/json" \
  -d "{\"currentPassword\":\"WrongPassword1\",\"newPassword\":\"AnotherNew@1\"}")

[[ "$wrong_status" == "400" || "$wrong_status" == "401" || "$wrong_status" == "422" ]] \
  && ok "Step 11: Wrong password returns error status ($wrong_status)" \
  || fail "Step 11" "Expected 400/401/422, got $wrong_status"

# ─── Step 12: AC8 — Refresh token validity ────────────────────────────────────
echo ""
echo "── Step 12: AC8 — Refresh token validity ────────────────────────"

refresh_token=$(get_refresh "$tokens2")
if [[ -n "$refresh_token" && "$refresh_token" != "None" ]]; then
  refresh_resp=$(curl -s -X POST "$BASE/api/v1/auth/refresh" \
    -H "Content-Type: application/json" \
    -d "{\"refreshToken\":\"$refresh_token\"}")
  new_access_from_refresh=$(get_access "$refresh_resp")
  [[ -n "$new_access_from_refresh" && "$new_access_from_refresh" != "None" ]] \
    && ok "Step 12: Refresh token valid — new access token issued" \
    || fail "Step 12" "Refresh failed — resp: $refresh_resp"
else
  fail "Step 12" "No refresh token in tokens2"
fi

# ─── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════════════"
echo " Results: ${PASS} PASS / ${FAIL} FAIL"
echo "════════════════════════════════════════════════════════════════"
[[ "$FAIL" -eq 0 ]] && exit 0 || exit 1
