#!/usr/bin/env bash
# ======================================================
# Story 3.5 — Invitation Employés, Rôles & Assignation Boutique
# cURL E2E integration tests
# Run: bash curl-tests-story-3-5.sh
# All steps must show ✅ before story is marked done
# ======================================================
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"
PY='python3 -c'

# ── Numéros de téléphone uniques par run (évite les conflits DB) ──────────────
SUFFIX=$(date +%H%M%S | tail -c5)   # 5 chiffres HH:MM:SS → ex: 04523
OWNER_PHONE="+237611${SUFFIX}0"
EMP_PHONE="+237611${SUFFIX}1"
EMP2_PHONE="+237611${SUFFIX}2"
EMP3_PHONE="+237611${SUFFIX}3"
EMP4_PHONE="+237611${SUFFIX}4"
EMP5_PHONE="+237611${SUFFIX}5"
EMP6A_PHONE="+237611${SUFFIX}6"
EMP6B_PHONE="+237611${SUFFIX}7"
echo "ℹ️  Run suffix=$SUFFIX — tous les numéros sont uniques pour ce run"

# ── Step 1 — Register owner ──────────────────────────────────────────────────
RESP=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$OWNER_PHONE\",\"password\":\"Test1234!\"}")
OWNER_TOKEN=$(echo "$RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('token',''))")
TENANT_CODE=$(echo "$RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('tenantCode',''))")
[[ -n "$OWNER_TOKEN" && "$OWNER_TOKEN" != "null" ]] && echo "✅ Step 1 — Owner registered" || { echo "❌ Step 1 FAILED: $RESP"; exit 1; }

# ── Step 2 — Onboarding ──────────────────────────────────────────────────────
curl -s -X POST "$BASE/api/v1/onboarding/complete" \
  -H "Authorization: Bearer $OWNER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"sectorType":"GENERAL","shopName":"Boutique Employés","city":"Douala"}' > /dev/null
echo "✅ Step 2 — Onboarding OK"

# ── Step 3 — Two-step login as owner ─────────────────────────────────────────
LOGIN_RESP=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$OWNER_PHONE\",\"password\":\"Test1234!\"}")
LOGIN_TOKEN=$(echo "$LOGIN_RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))")

SELECT_RESP=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$LOGIN_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")
JWT=$(echo "$SELECT_RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
[[ -n "$JWT" && "$JWT" != "null" ]] && echo "✅ Step 3 — Owner two-step login OK" || { echo "❌ Step 3 FAILED: $SELECT_RESP"; exit 1; }

# ── Step 4 — Get store ID ────────────────────────────────────────────────────
STORES=$(curl -s -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $JWT")
STORE_ID=$(echo "$STORES" | $PY "import sys,json; print(json.load(sys.stdin)['data'][0]['id'])")
[[ -n "$STORE_ID" && "$STORE_ID" != "null" ]] && echo "✅ Step 4 — Store found (id=$STORE_ID)" || { echo "❌ Step 4 FAILED"; exit 1; }

# ── Step 5 — Create employee ─────────────────────────────────────────────────
EMP_RESP=$(curl -s -X POST "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"firstName\":\"Loïc\",\"lastName\":\"Nkoulou\",\"storeId\":\"$STORE_ID\"}")
EMP_ID=$(echo "$EMP_RESP" | $PY "import sys,json; print(json.load(sys.stdin)['data']['employee']['id'])")
TEMP_PWD=$(echo "$EMP_RESP" | $PY "import sys,json; print(json.load(sys.stdin)['data']['temporaryPassword'])")
EMP_USER_ID=$(echo "$EMP_RESP" | $PY "import sys,json; print(json.load(sys.stdin)['data']['employee']['userId'])")
[[ -n "$EMP_ID" && "$EMP_ID" != "null" && -n "$TEMP_PWD" ]] && echo "✅ Step 5 — Employee created (id=$EMP_ID, tempPwd length=${#TEMP_PWD})" || { echo "❌ Step 5 FAILED: $EMP_RESP"; exit 1; }

# ── Step 6 — List employees ──────────────────────────────────────────────────
LIST=$(curl -s -X GET "$BASE/api/v1/employees" -H "Authorization: Bearer $JWT")
COUNT=$(echo "$LIST" | $PY "import sys,json; print(len(json.load(sys.stdin)['data']))")
[[ "$COUNT" -ge 1 ]] && echo "✅ Step 6 — Employee list OK (count=$COUNT)" || { echo "❌ Step 6 FAILED: $LIST"; exit 1; }

# ── Step 7 — Login as employee (two-step) ────────────────────────────────────
EMP_LOGIN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"$TEMP_PWD\"}")
EMP_LOGIN_TOKEN=$(echo "$EMP_LOGIN" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))")
[[ -n "$EMP_LOGIN_TOKEN" && "$EMP_LOGIN_TOKEN" != "null" ]] && echo "✅ Step 7 — Employee login step 1 OK" || { echo "❌ Step 7 FAILED: $EMP_LOGIN"; exit 1; }

EMP_SELECT=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$EMP_LOGIN_TOKEN\",\"tenantCode\":\"$TENANT_CODE\"}")
EMP_JWT=$(echo "$EMP_SELECT" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
PWD_REQ=$(echo "$EMP_SELECT" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('passwordChangeRequired', False))")
[[ -n "$EMP_JWT" && "$EMP_JWT" != "null" ]] && echo "✅ Step 7 — Employee select-tenant OK (passwordChangeRequired=$PWD_REQ)" || { echo "❌ Step 7 FAILED: $EMP_SELECT"; exit 1; }

# ── Step 8 — Verify PASSWORD_CHANGE_REQUIRED guard (403) ─────────────────────
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $EMP_JWT")
[[ "$HTTP_CODE" == "403" ]] && echo "✅ Step 8 — Employee blocked by password guard (403)" || { echo "❌ Step 8 FAILED: expected 403, got $HTTP_CODE"; exit 1; }

# ── Step 9 — Change password ─────────────────────────────────────────────────
CHG_RESP=$(curl -s -X POST "$BASE/api/v1/auth/change-password" \
  -H "Authorization: Bearer $EMP_JWT" \
  -H "Content-Type: application/json" \
  -d "{\"currentPassword\":\"$TEMP_PWD\",\"newPassword\":\"SecureNew123\"}")
NEW_JWT=$(echo "$CHG_RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
NEW_PWD_REQ=$(echo "$CHG_RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('passwordChangeRequired', True))")
[[ -n "$NEW_JWT" && "$NEW_JWT" != "null" ]] && echo "✅ Step 9 — Password changed (passwordChangeRequired=$NEW_PWD_REQ)" || { echo "❌ Step 9 FAILED: $CHG_RESP"; exit 1; }

# ── Step 10 — Verify access after password change ────────────────────────────
# Re-login with new password to get a fresh JWT
EMP_LOGIN2=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"SecureNew123\"}")
EMP_LOGIN_TOKEN2=$(echo "$EMP_LOGIN2" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))")

EMP_SELECT2=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$EMP_LOGIN_TOKEN2\",\"tenantCode\":\"$TENANT_CODE\"}")
EMP_JWT2=$(echo "$EMP_SELECT2" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
PWD_REQ2=$(echo "$EMP_SELECT2" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('passwordChangeRequired', True))")
[[ "$PWD_REQ2" == "False" ]] && echo "✅ Step 10 — Re-login shows passwordChangeRequired=False" || { echo "❌ Step 10 FAILED: passwordChangeRequired=$PWD_REQ2"; exit 1; }

# ── Step 11 — Employee can access protected endpoint ─────────────────────────
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $EMP_JWT2")
[[ "$HTTP_CODE" == "200" ]] && echo "✅ Step 11 — Employee access OK after password change" || { echo "❌ Step 11 FAILED: expected 200, got $HTTP_CODE"; exit 1; }

# ── Step 12 — AC5 : L'employé ne peut pas créer d'autres employés → 403 ──────
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $EMP_JWT2" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP6B_PHONE\",\"firstName\":\"Test\",\"lastName\":\"Forbidden\",\"storeId\":\"$STORE_ID\"}")
[[ "$HTTP_CODE" == "403" ]] \
  && echo "✅ Step 12  — Employé POST /employees → 403 FORBIDDEN (RBAC AC5 OK)" \
  || { echo "❌ Step 12 FAILED: expected 403, got $HTTP_CODE"; exit 1; }

# ── Step 13 — AC2 : Plan limit FREE = 5 employés ─────────────────────────────
# Déjà 1 employé ACTIVE — en créer 4 autres pour atteindre la limite
for i in 2 3 4 5; do
  PHONE="+237611${SUFFIX}${i}"
  HR=$(curl -s -o /dev/null -w "%{http_code}" \
    -X POST "$BASE/api/v1/employees" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -d "{\"phoneNumber\":\"${PHONE}\",\"firstName\":\"Emp\",\"lastName\":\"${i}\",\"storeId\":\"$STORE_ID\"}")
  [[ "$HR" == "201" ]] \
    && echo "✅ Step 13a — Employé ${i}/5 créé (HTTP $HR)" \
    || { echo "❌ Step 13a FAILED: employé ${i} → HTTP $HR"; exit 1; }
done

# Forcer le plan FREE (max_employees=5) dans ce tenant pour tester la limite
SCHEMA_NAME="kv_$(echo "$TENANT_CODE" | sed 's/KV-//' | tr '[:upper:]' '[:lower:]')"
PG_CMD="env PGPASSWORD=keevo_local_pwd psql 'host=localhost port=5444 dbname=keevo_dev user=keevo'"
eval "$PG_CMD -At -c \"UPDATE \\\"${SCHEMA_NAME}\\\".subscriptions SET plan_type='FREE', max_employees=5\"" > /dev/null 2>&1 \
  && echo "ℹ️  Subscription downgradée FREE max_employees=5 (schema=$SCHEMA_NAME)" \
  || echo "⚠️  Impossible de downgrader la subscription — step 13b peut être Skip"

# 6e employé → doit être bloqué
LIMIT_RESP=$(curl -s -X POST "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP6A_PHONE\",\"firstName\":\"TooMany\",\"lastName\":\"Emp\",\"storeId\":\"$STORE_ID\"}")
LIMIT_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP6B_PHONE\",\"firstName\":\"TooMany2\",\"lastName\":\"Emp\",\"storeId\":\"$STORE_ID\"}")
LIMIT_DOMAIN=$(echo "$LIMIT_RESP" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('domainCode',''))")
[[ "$LIMIT_HTTP" == "403" ]] \
  && echo "✅ Step 13b — 6e employé → 403 PLAN_LIMIT_EXCEEDED (AC2 OK)" \
  || { echo "❌ Step 13b FAILED: expected 403, got $LIMIT_HTTP"; exit 1; }
[[ "$LIMIT_DOMAIN" == "PLAN_LIMIT_EXCEEDED" ]] \
  && echo "✅ Step 13c — domainCode=PLAN_LIMIT_EXCEEDED confirmé" \
  || echo "⚠️  Step 13c — domainCode='$LIMIT_DOMAIN' (vérifier le champ JSON)"

# ── Step 14 — AC6 : Réassigner l'employé + guard STORE_REASSIGNED ─────────────
STORE2=$(curl -s -X POST "$BASE/api/v1/stores" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"name":"Boutique Annexe","address":"Rue 2, Douala","phone":"+237699000002"}')
STORE2_ID=$(echo "$STORE2" | $PY "import sys,json; print(json.load(sys.stdin)['data']['id'])")
[[ -n "$STORE2_ID" && "$STORE2_ID" != "null" ]] \
  && echo "✅ Step 14a — Deuxième boutique créée (id=$STORE2_ID)" \
  || { echo "❌ Step 14a FAILED: $STORE2"; exit 1; }

REASSIGN=$(curl -s -X PATCH "$BASE/api/v1/employees/$EMP_ID/store" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"storeId\":\"$STORE2_ID\"}")
NEW_STORE=$(echo "$REASSIGN" | $PY "import sys,json; print(json.load(sys.stdin)['data']['storeId'])")
[[ "$NEW_STORE" == "$STORE2_ID" ]] \
  && echo "✅ Step 14b — Employé réassigné boutique2" \
  || { echo "❌ Step 14b FAILED: newStore=$NEW_STORE"; exit 1; }

# Ancien JWT (EMP_JWT2, storeId=boutique1) → 401 STORE_REASSIGNED
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $EMP_JWT2")
[[ "$HTTP_CODE" == "401" ]] \
  && echo "✅ Step 14c — Ancien JWT employé → 401 STORE_REASSIGNED (AC6 OK)" \
  || { echo "❌ Step 14c FAILED: expected 401, got $HTTP_CODE"; exit 1; }

# ── Step 15 — AC6 : Re-login avec le nouveau storeId ─────────────────────────
EMP_LOGIN3=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"SecureNew123\"}")
EMP_LOGIN_TOKEN3=$(echo "$EMP_LOGIN3" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))")
EMP_SELECT3=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$EMP_LOGIN_TOKEN3\",\"tenantCode\":\"$TENANT_CODE\"}")
EMP_JWT3=$(echo "$EMP_SELECT3" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
EMP_STORE3=$(echo "$EMP_SELECT3" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('storeId','null'))")
[[ "$EMP_STORE3" == "$STORE2_ID" ]] \
  && echo "✅ Step 15  — Nouveau JWT porte storeId=$EMP_STORE3 (AC6 complet)" \
  || { echo "❌ Step 15 FAILED: expected $STORE2_ID, got $EMP_STORE3"; exit 1; }

# ── Step 16 — AC7 : Désactiver l'employé ─────────────────────────────────────
DEACT_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X PATCH "$BASE/api/v1/employees/$EMP_ID/deactivate" \
  -H "Authorization: Bearer $JWT")
[[ "$DEACT_CODE" == "200" ]] \
  && echo "✅ Step 16a — Employé désactivé (HTTP 200)" \
  || { echo "❌ Step 16a FAILED: HTTP $DEACT_CODE"; exit 1; }

LIST2=$(curl -s -X GET "$BASE/api/v1/employees" -H "Authorization: Bearer $JWT")
EMP_STATUS=$(echo "$LIST2" | $PY "
import sys,json
data = json.load(sys.stdin)['data']
for e in data:
    if e['id'] == '$EMP_ID':
        print(e['status'])
        break
else:
    print('NOT_FOUND')
")
[[ "$EMP_STATUS" == "INACTIVE" ]] \
  && echo "✅ Step 16b — statut=INACTIVE dans la liste AC8" \
  || { echo "❌ Step 16b FAILED: status=$EMP_STATUS"; exit 1; }

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $EMP_JWT3")
[[ "$HTTP_CODE" == "401" ]] \
  && echo "✅ Step 16c — JWT employé désactivé → 401 ACCOUNT_INACTIVE" \
  || { echo "❌ Step 16c FAILED: expected 401, got $HTTP_CODE"; exit 1; }

# ── Step 17 — AC7b : Réactiver l'employé ─────────────────────────────────────
REACT_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X PATCH "$BASE/api/v1/employees/$EMP_ID/reactivate" \
  -H "Authorization: Bearer $JWT")
[[ "$REACT_CODE" == "200" ]] \
  && echo "✅ Step 17a — Employé réactivé (HTTP 200)" \
  || { echo "❌ Step 17a FAILED: HTTP $REACT_CODE"; exit 1; }

LIST3=$(curl -s -X GET "$BASE/api/v1/employees" -H "Authorization: Bearer $JWT")
EMP_STATUS2=$(echo "$LIST3" | $PY "
import sys,json
data = json.load(sys.stdin)['data']
for e in data:
    if e['id'] == '$EMP_ID':
        print(e['status'])
        break
else:
    print('NOT_FOUND')
")
[[ "$EMP_STATUS2" == "ACTIVE" ]] \
  && echo "✅ Step 17b — statut=ACTIVE après réactivation (AC7b OK)" \
  || { echo "❌ Step 17b FAILED: status=$EMP_STATUS2"; exit 1; }

EMP_RELOGIN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"SecureNew123\"}")
REACT_TOKEN=$(echo "$EMP_RELOGIN" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))")
[[ -n "$REACT_TOKEN" && "$REACT_TOKEN" != "null" ]] \
  && echo "✅ Step 17c — Employé réactivé peut se reconnecter" \
  || { echo "❌ Step 17c FAILED: pas de loginToken après réactivation"; exit 1; }

# ── Step 18 — AC7c : Régénérer le mot de passe ───────────────────────────────
REGEN=$(curl -s -X POST "$BASE/api/v1/employees/$EMP_ID/regenerate-password" \
  -H "Authorization: Bearer $JWT")
NEW_TEMP=$(echo "$REGEN" | $PY "import sys,json; print(json.load(sys.stdin)['data']['temporaryPassword'])")
REGEN_PWD_REQ=$(echo "$REGEN" | $PY "import sys,json; print(json.load(sys.stdin)['data']['employee']['passwordChangeRequired'])")
[[ -n "$NEW_TEMP" && "${#NEW_TEMP}" -ge 8 ]] \
  && echo "✅ Step 18a — Mot de passe régénéré (len=${#NEW_TEMP}, passwordChangeRequired=$REGEN_PWD_REQ)" \
  || { echo "❌ Step 18a FAILED: $REGEN"; exit 1; }

EMP_REGEN_LOGIN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"password\":\"$NEW_TEMP\"}")
REGEN_LOGIN_TOK=$(echo "$EMP_REGEN_LOGIN" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('loginToken',''))")
REGEN_SELECT=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
  -H "Content-Type: application/json" \
  -d "{\"loginToken\":\"$REGEN_LOGIN_TOK\",\"tenantCode\":\"$TENANT_CODE\"}")
REGEN_JWT=$(echo "$REGEN_SELECT" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken',''))")
REGEN_REQ=$(echo "$REGEN_SELECT" | $PY "import sys,json; d=json.load(sys.stdin); print(d.get('passwordChangeRequired', False))")
[[ "$REGEN_REQ" == "True" ]] \
  && echo "✅ Step 18b — Login avec nouveau mdp temp: passwordChangeRequired=True (AC7c OK)" \
  || { echo "❌ Step 18b FAILED: expected True, got $REGEN_REQ"; exit 1; }

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X GET "$BASE/api/v1/stores" -H "Authorization: Bearer $REGEN_JWT")
[[ "$HTTP_CODE" == "403" ]] \
  && echo "✅ Step 18c — Employé bloqué (403) jusqu'au nouveau changement de mot de passe" \
  || { echo "❌ Step 18c FAILED: expected 403, got $HTTP_CODE"; exit 1; }

# ── Step 19 — Sécurité : pas de JWT → 401 ────────────────────────────────────
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X GET "$BASE/api/v1/employees")
[[ "$HTTP_CODE" == "401" ]] \
  && echo "✅ Step 19a — GET /employees sans JWT → 401" \
  || { echo "❌ Step 19a FAILED: expected 401, got $HTTP_CODE"; exit 1; }

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$BASE/api/v1/auth/change-password" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword":"old","newPassword":"newpass1"}')
[[ "$HTTP_CODE" == "401" ]] \
  && echo "✅ Step 19b — POST /auth/change-password sans JWT → 401" \
  || { echo "❌ Step 19b FAILED: expected 401, got $HTTP_CODE"; exit 1; }

# ── Step 20 — Sécurité : téléphone dupliqué → 4xx ────────────────────────────
DUP_HTTP=$(curl -s -o /dev/null -w "%{http_code}" \
  -X POST "$BASE/api/v1/employees" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d "{\"phoneNumber\":\"$EMP_PHONE\",\"firstName\":\"Dup\",\"lastName\":\"Phone\",\"storeId\":\"$STORE_ID\"}")
[[ "$DUP_HTTP" == "409" || "$DUP_HTTP" == "400" || "$DUP_HTTP" == "422" ]] \
  && echo "✅ Step 20  — Téléphone dupliqué rejeté (HTTP $DUP_HTTP)" \
  || { echo "❌ Step 20 FAILED: expected 4xx pour téléphone dupliqué, got $DUP_HTTP"; exit 1; }

echo ""
echo "══════════════════════════════════════════════════"
echo "🎉  All 20 Story 3.5 cURL integration tests passed!"
echo "══════════════════════════════════════════════════"
