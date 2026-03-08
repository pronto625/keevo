#!/usr/bin/env bash
# =============================================================================
# Story 1.8 — cURL smoke tests (jq-free, uses python3 for JSON parsing)
# =============================================================================
# Tests: AC1 audit entries written, AC2 immutable (403), AC3 query filters,
#        AC4 tenant isolation, AC5 French errors, AC6 data returned
#
# Requires: curl, python3
# Usage:    bash curl-tests-story-1-8.sh [base_url]
# Default:  BASE=http://localhost:8080
# =============================================================================

BASE="${1:-http://localhost:8080}"
PASS=0
FAIL=0

section() { printf "\n\033[0;36m──────────────────────────────────────────────────────────────\033[0m\n"; printf "\033[0;36m  %s\033[0m\n" "$1"; printf "\033[0;36m──────────────────────────────────────────────────────────────\033[0m\n"; }
ok()      { printf "  \033[0;32m[PASS]  %s\033[0m\n" "$1"; PASS=$((PASS+1)); }
fail()    { printf "  \033[0;31m[FAIL]  %s\033[0m\n" "$1"; FAIL=$((FAIL+1)); }
check()   { if [ "$2" = "$3" ]; then ok "$1"; else fail "$1 (got: '$2', expected: '$3')"; fi; }
j()       { echo "$1" | python3 -c "import json,sys; d=json.load(sys.stdin); print($2)" 2>/dev/null || echo ""; }
ts()      { python3 -c "import time; print(str(int(time.time()*1000))[-8:])" 2>/dev/null; }

# ── Step 1: Register user A ─────────────────────────────────────────────────
section "Step 1: Register user A"
PHONE_A="+237600$(ts)"
REG_A=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_A\",\"password\":\"Test1234!\"}")
USER_ID_A=$(j "$REG_A" "d.get('userId','')")
TC_A=$(j "$REG_A" "d.get('tenantCode','')")
check "Register user A returns userId" "$(test -n "$USER_ID_A" && echo ok || echo empty)" "ok"
check "Register user A returns tenantCode" "$(test -n "$TC_A" && echo ok || echo empty)" "ok"

# ── Step 2+3: Login + Select-tenant A ────────────────────────────────────────
section "Step 2-3: Login + select-tenant A"
LOGIN_A=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_A\",\"password\":\"Test1234!\"}")
LT_A=$(j "$LOGIN_A" "d.get('loginToken','')")
SEL_A=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LT_A\",\"tenantCode\":\"$TC_A\"}")
JWT_A=$(j "$SEL_A" "d.get('accessToken','')")
check "Login returns loginToken" "$(test -n "$LT_A" && echo ok || echo empty)" "ok"
check "Select-tenant returns accessToken" "$(test -n "$JWT_A" && echo ok || echo empty)" "ok"

# ── Step 4: GET /audit (no params) → 200, ≥1 entry ──────────────────────────
section "Step 4: GET /audit (no params) -> 200 with >=1 entry"
RESP4=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/audit" -H "Authorization: Bearer $JWT_A")
BODY4=$(echo "$RESP4" | head -n1); CODE4=$(echo "$RESP4" | tail -n1)
COUNT4=$(j "$BODY4" "len(d.get('data',[]))")
check "GET /audit returns 200" "$CODE4" "200"
check "Full log has >=1 entry" "$(test "${COUNT4:-0}" -ge 1 2>/dev/null && echo ok || echo empty)" "ok"

# ── Step 5: GET with entityType+entityId filter ───────────────────────────────
section "Step 5: GET /audit?entityType=User&entityId={id} -> USER_REGISTERED present"
RESP5=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/audit?entityType=User&entityId=$USER_ID_A" -H "Authorization: Bearer $JWT_A")
BODY5=$(echo "$RESP5" | head -n1); CODE5=$(echo "$RESP5" | tail -n1)
HAS_REG=$(j "$BODY5" "'USER_REGISTERED' in [e.get('action') for e in d.get('data',[])] and 'yes' or 'no'")
check "GET /audit?entityType=User&entityId={id} returns 200" "$CODE5" "200"
check "Response contains USER_REGISTERED action" "$HAS_REG" "yes"

# ── Step 6: DELETE → 403 ─────────────────────────────────────────────────────
section "Step 6: DELETE /audit/{id} -> 403 AUDIT_IMMUTABLE"
ENTRY_ID=$(j "$BODY5" "d['data'][0]['id'] if d.get('data') else '00000000-0000-0000-0000-000000000000'")
DEL_RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$BASE/api/v1/audit/$ENTRY_ID" -H "Authorization: Bearer $JWT_A")
DEL_BODY=$(echo "$DEL_RESP" | head -n1); DEL_CODE=$(echo "$DEL_RESP" | tail -n1)
DEL_DOMAIN=$(j "$DEL_BODY" "d.get('domainCode','')")
check "DELETE /audit/{id} returns 403" "$DEL_CODE" "403"
check "DELETE domainCode=AUDIT_IMMUTABLE" "$DEL_DOMAIN" "AUDIT_IMMUTABLE"

# ── Step 7: PUT → 403 ────────────────────────────────────────────────────────
section "Step 7: PUT /audit/{id} -> 403 AUDIT_IMMUTABLE"
PUT_RESP=$(curl -s -w "\n%{http_code}" -X PUT "$BASE/api/v1/audit/$ENTRY_ID" \
  -H "Authorization: Bearer $JWT_A" -H "Content-Type: application/json" -d '{"action":"HACKED"}')
PUT_BODY=$(echo "$PUT_RESP" | head -n1); PUT_CODE=$(echo "$PUT_RESP" | tail -n1)
PUT_DOMAIN=$(j "$PUT_BODY" "d.get('domainCode','')")
check "PUT /audit/{id} returns 403" "$PUT_CODE" "403"
check "PUT domainCode=AUDIT_IMMUTABLE" "$PUT_DOMAIN" "AUDIT_IMMUTABLE"

# ── Step 8: No JWT → 401 ──────────────────────────────────────────────────────
section "Step 8: GET /audit without JWT -> 401"
NOAUTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/audit")
check "GET /audit without JWT returns 401" "$NOAUTH" "401"

# ── Step 9: Unknown entityId → 200 empty list ────────────────────────────────
section "Step 9: GET /audit?entityType=Product&entityId=unknown -> 200 empty"
RESP9=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/audit?entityType=Product&entityId=00000000-0000-0000-0000-000000000000" \
  -H "Authorization: Bearer $JWT_A")
BODY9=$(echo "$RESP9" | head -n1); CODE9=$(echo "$RESP9" | tail -n1)
EMP9=$(j "$BODY9" "len(d.get('data',[]))")
check "GET /audit with unknown entityId returns 200" "$CODE9" "200"
check "Response data is empty list" "$EMP9" "0"

# ── Step 10: French error ─────────────────────────────────────────────────────
section "Step 10: 403 error field is in French"
FR_MSG=$(j "$DEL_BODY" "d.get('error','')")
HAS_FR=$(python3 -c "msg='''$FR_MSG'''.lower(); print('yes' if 'journal' in msg or 'modifi' in msg else 'no')" 2>/dev/null)
check "403 error is in French (contains 'journal' or 'modifi')" "$HAS_FR" "yes"

# ── Step 11: Tenant isolation ─────────────────────────────────────────────────
section "Step 11: Tenant isolation - Tenant B cannot see Tenant A entries"
PHONE_B="+237600$(ts)"
REG_B=$(curl -s -X POST "$BASE/api/v1/auth/register" -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_B\",\"password\":\"Test1234!\"}")
TC_B=$(j "$REG_B" "d.get('tenantCode','')")
LOGIN_B=$(curl -s -X POST "$BASE/api/v1/auth/login" -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$PHONE_B\",\"password\":\"Test1234!\"}")
LT_B=$(j "$LOGIN_B" "d.get('loginToken','')")
JWT_B=$(j "$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LT_B\",\"tenantCode\":\"$TC_B\"}")" "d.get('accessToken','')")
B_FULL=$(curl -s "$BASE/api/v1/audit" -H "Authorization: Bearer $JWT_B")
SEES_A=$(j "$B_FULL" "any(e.get('userId')=='$USER_ID_A' for e in d.get('data',[])) and 'yes' or 'no'")
check "Tenant B does NOT see Tenant A userId in its full log" "$SEES_A" "no"

# ── Summary ───────────────────────────────────────────────────────────────────
TOTAL=$((PASS + FAIL))
printf "\n\033[0;36m══════════════════════════════════════════════════════════════\033[0m\n"
printf "  Story 1.8 cURL Results: %d/%d tests passed\n" "$PASS" "$TOTAL"
if [ "$FAIL" -gt 0 ]; then
  printf "  \033[0;31mStatus: FAILED - %d test(s) failed\033[0m\n" "$FAIL"
  printf "\033[0;36m══════════════════════════════════════════════════════════════\033[0m\n"
  exit 1
else
  printf "  \033[0;32mStatus: ALL %d TESTS PASSED\033[0m\n" "$PASS"
fi
printf "\033[0;36m══════════════════════════════════════════════════════════════\033[0m\n"
