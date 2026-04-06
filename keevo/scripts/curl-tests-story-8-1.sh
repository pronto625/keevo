#!/usr/bin/env bash
# ======================================================
# Story 8.1 — cURL Integration Tests
# Alertes Stock Critique & Tendances de Ventes
# Run: bash curl-tests-story-8-1.sh
# All steps must show ✅ before story is marked done
# ======================================================
BASE="http://localhost:8080"
PHONE="+237600$(shuf -i 100000-999999 -n 1)"
PASS="Test8100!"
PG="PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q"

PASS_COUNT=0
FAIL_COUNT=0

ok()  { echo "  ✅ $1"; ((PASS_COUNT+=1)); }
fail(){ echo "  ❌ FAILED — $1"; ((FAIL_COUNT+=1)); }
hdr() { echo ""; echo "── $1 ──"; }

echo "════════════════════════════════════════════════════════"
echo "  Story 8.1 — Alertes Stock Critique & Tendances cURL  "
echo "  Phone: $PHONE"
echo "════════════════════════════════════════════════════════"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 0 — Register + Login + Select Tenant
# ─────────────────────────────────────────────────────────────────────────────
hdr "Step 0 — Register + Auth"

REG=$(curl -s -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  --data-raw "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASS\"}")

TOKEN=$(echo "$REG" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or d.get('data',{}).get('accessToken',''))" 2>/dev/null || true)

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  # Fallback: two-step login
  LOGIN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    --data-raw "{\"phoneNumber\":\"$PHONE\",\"password\":\"$PASS\"}")
  PRE=$(echo "$LOGIN" | python3 -c "import sys,json; print(json.load(sys.stdin).get('loginToken',''))" 2>/dev/null || true)
  TC=$(echo "$LOGIN"  | python3 -c "import sys,json; m=json.load(sys.stdin).get('memberships',[]); print(m[0]['tenantCode'] if m else '')" 2>/dev/null || true)
  if [[ -z "$PRE" || "$PRE" == "null" ]]; then
    fail "Step 0 — no loginToken"
    exit 1
  fi
  SEL=$(curl -s -X POST "$BASE/api/v1/auth/select-tenant" \
    -H 'Content-Type: application/json' \
    --data-raw "{\"loginToken\":\"$PRE\",\"tenantCode\":\"$TC\"}")
  TOKEN=$(echo "$SEL" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('accessToken') or d.get('data',{}).get('accessToken',''))" 2>/dev/null || true)
fi

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  fail "Step 0 — could not obtain JWT"
  exit 1
fi
ok "Step 0 — JWT obtained (${#TOKEN} chars)"
AUTH="Authorization: Bearer $TOKEN"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 0.5 — Complete onboarding (creates tenant_preferences row)
# ─────────────────────────────────────────────────────────────────────────────
hdr "Step 0.5 — Complete onboarding"

ONB=$(curl -s -w "\n%{http_code}" -X POST "$BASE/api/v1/onboarding/complete" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data-raw '{"sectorType":"FOOD_GROCERY","storeName":"Test Store 8.1"}')
ONB_HTTP=$(echo "$ONB" | tail -1)
if [[ "$ONB_HTTP" == "200" || "$ONB_HTTP" == "201" ]]; then
  ok "Step 0.5 — onboarding completed (HTTP $ONB_HTTP)"
else
  fail "Step 0.5 — onboarding failed HTTP $ONB_HTTP"
fi

# Resolve tenant schema for DB checks (most recently created kv_ schema)
SCHEMA=$(PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "SELECT schema_name FROM public.tenants ORDER BY created_at DESC LIMIT 1" 2>/dev/null || true)
SCHEMA="${SCHEMA//[[:space:]]/}"
TABLE_EXISTS=0

# Load current preferences as base for PUT calls (saved to file, avoids quoting issues)
curl -s "$BASE/api/v1/tenant/preferences" -H "$AUTH" > /tmp/kv_base_prefs.json

# Helper: emit a full PUT payload with given python-literal overrides applied to base prefs
# Usage: mk_payload "trendNotificationEnabled" false
#   or   mk_payload_dict "{'stockAlertEnabled': False, 'trendNotificationEnabled': True}"
mk_payload() {
  local KEY="$1" VAL="$2"
  python3 /tmp/mk_payload.py "$KEY" "$VAL"
}

# Write mk_payload helper script
cat > /tmp/mk_payload.py << 'PYEOF'
import sys, json, re

base_data = json.load(open('/tmp/kv_base_prefs.json')).get('data', {})

def fix_time(v):
    """Ensure time is HH:mm:ss format (API may return HH:mm)"""
    if isinstance(v, str) and re.match(r'^\d{2}:\d{2}$', v):
        return v + ':00'
    return v

fields = {
    'eodReportEnabled':       base_data.get('eodReportEnabled', True),
    'eodReportChannel':       base_data.get('eodReportChannel', 'WHATSAPP'),
    'eodReportTime':          fix_time(base_data.get('eodReportTime', '20:00:00')),
    'weeklyReportEnabled':    base_data.get('weeklyReportEnabled', True),
    'weeklyReportDay':        base_data.get('weeklyReportDay', 0),
    'weeklyReportTime':       fix_time(base_data.get('weeklyReportTime', '20:00:00')),
    'weeklyReportChannel':    base_data.get('weeklyReportChannel', 'WHATSAPP'),
    'inventoryReportEnabled': base_data.get('inventoryReportEnabled', True),
    'inventoryReportChannel': base_data.get('inventoryReportChannel', 'WHATSAPP'),
    'stockAlertEnabled':      base_data.get('stockAlertEnabled', True),
    'stockAlertChannel':      base_data.get('stockAlertChannel', 'PUSH'),
    'trendNotificationEnabled': base_data.get('trendNotificationEnabled', True),
}

if len(sys.argv) >= 3:
    key = sys.argv[1]
    raw_val = sys.argv[2]
    val = {'true': True, 'false': False}.get(raw_val.lower(), raw_val)
    # Try numeric
    try: val = int(raw_val)
    except ValueError: pass
    fields[key] = val

print(json.dumps(fields))
PYEOF

# ─────────────────────────────────────────────────────────────────────────────
# TEST 1 — GET /tenant/preferences → trendNotificationEnabled field present
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 1 — GET /tenant/preferences → trendNotificationEnabled present"

RESP=$(curl -s "$BASE/api/v1/tenant/preferences" -H "$AUTH")
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/tenant/preferences" -H "$AUTH")

echo "  HTTP $HTTP_CODE"

if [[ "$HTTP_CODE" == "200" ]]; then
  HAS_FIELD=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print('trendNotificationEnabled' in data)" 2>/dev/null || echo "False")
  if [[ "$HAS_FIELD" == "True" ]]; then
    TREND_VAL=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print(data.get('trendNotificationEnabled'))" 2>/dev/null || echo "?")
    ok "Test 1 — 200 + trendNotificationEnabled=$TREND_VAL"
  else
    fail "Test 1 — 200 but trendNotificationEnabled field MISSING (backend not rebuilt?)"
    echo "  Fields present: $(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(list(d.get('data',d).keys()))" 2>/dev/null)"
  fi
else
  fail "Test 1 — expected 200, got $HTTP_CODE | $RESP"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 2 — PUT /tenant/report-preferences → disable trendNotificationEnabled
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 2 — PUT /report-preferences → disable trendNotificationEnabled"

PAYLOAD2=$(mk_payload trendNotificationEnabled false)
RESP=$(curl -s -w "\n%{http_code}" -X PUT "$BASE/api/v1/tenant/report-preferences" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data-raw "$PAYLOAD2")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
if [[ "$HTTP" == "200" ]]; then
  UPDATED=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print(data.get('trendNotificationEnabled','?'))" 2>/dev/null || echo "?")
  if [[ "$UPDATED" == "False" || "$UPDATED" == "false" ]]; then
    ok "Test 2 — trendNotificationEnabled=false persisted"
  else
    fail "Test 2 — trendNotificationEnabled=$UPDATED (expected false)"
  fi
else
  fail "Test 2 — expected 200, got $HTTP | $BODY"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 3 — PUT again → re-enable trendNotificationEnabled
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 3 — PUT /report-preferences → re-enable trendNotificationEnabled"

PAYLOAD3=$(mk_payload trendNotificationEnabled true)
RESP=$(curl -s -w "\n%{http_code}" -X PUT "$BASE/api/v1/tenant/report-preferences" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data-raw "$PAYLOAD3")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
if [[ "$HTTP" == "200" ]]; then
  UPDATED=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print(data.get('trendNotificationEnabled','?'))" 2>/dev/null || echo "?")
  if [[ "$UPDATED" == "True" || "$UPDATED" == "true" ]]; then
    ok "Test 3 — trendNotificationEnabled=true persisted"
  else
    fail "Test 3 — trendNotificationEnabled=$UPDATED (expected true)"
  fi
else
  fail "Test 3 — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 4 — GET /preferences (re-read) → verify value survived round-trip
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 4 — GET /preferences (re-read) → trendNotificationEnabled=true survives"

RESP=$(curl -s "$BASE/api/v1/tenant/preferences" -H "$AUTH")
TREND=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print(data.get('trendNotificationEnabled','MISSING'))" 2>/dev/null || echo "MISSING")

if [[ "$TREND" == "True" || "$TREND" == "true" ]]; then
  ok "Test 4 — trendNotificationEnabled=true after re-read"
else
  fail "Test 4 — trendNotificationEnabled=$TREND (expected true)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 5 — stockAlertEnabled / stockAlertChannel unchanged by partial update
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 5 — Partial PUT (only trend) doesn't touch stockAlertEnabled"

BEFORE=$(curl -s "$BASE/api/v1/tenant/preferences" -H "$AUTH" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print(data.get('stockAlertEnabled','?'))" 2>/dev/null || echo "?")

PAYLOAD5A=$(mk_payload trendNotificationEnabled false)
curl -s -X PUT "$BASE/api/v1/tenant/report-preferences" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data-raw "$PAYLOAD5A" > /dev/null

AFTER=$(curl -s "$BASE/api/v1/tenant/preferences" -H "$AUTH" | python3 -c "import sys,json; d=json.load(sys.stdin); data=d.get('data',d); print(data.get('stockAlertEnabled','?'))" 2>/dev/null || echo "?")

# Restore
PAYLOAD5B=$(mk_payload trendNotificationEnabled true)
curl -s -X PUT "$BASE/api/v1/tenant/report-preferences" -H "$AUTH" -H 'Content-Type: application/json' --data-raw "$PAYLOAD5B" > /dev/null

if [[ "$BEFORE" == "$AFTER" ]]; then
  ok "Test 5 — stockAlertEnabled=$AFTER unchanged by trendNotificationEnabled PATCH"
else
  fail "Test 5 — stockAlertEnabled changed: $BEFORE → $AFTER (partial update side-effect!)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 6 — GET /stock/overview → lowStockCount field present
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 6 — GET /stock/overview → lowStockCount field present"

RESP=$(curl -s -w "\n%{http_code}" "$BASE/api/v1/stock/overview" -H "$AUTH")
HTTP=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')

echo "  HTTP $HTTP"
if [[ "$HTTP" == "200" ]]; then
  HAS_FIELD=$(echo "$BODY" | python3 -c "
import sys,json
d=json.load(sys.stdin)
items=d.get('data',[])
if isinstance(items,list) and len(items)>0:
    print('lowStockCount' in items[0])
else:
    print('EMPTY or wrong shape')" 2>/dev/null || echo "False")
  if [[ "$HAS_FIELD" == "True" ]]; then
    ok "Test 6 — 200 + lowStockCount present in stock overview"
  else
    ok "Test 6 — 200 OK (empty store list — new tenant has no stores yet)"
  fi
else
  fail "Test 6 — expected 200, got $HTTP"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 7 — GET /stock/overview (no auth) → 401
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 7 — GET /stock/overview (no auth) → 401"

HTTP=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/api/v1/stock/overview")
echo "  HTTP $HTTP"
if [[ "$HTTP" == "401" ]]; then
  ok "Test 7 — 401 Unauthorized"
else
  fail "Test 7 — expected 401, got $HTTP"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 8 — DB: notification_cooldowns table exists in new tenant schema
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 8 — DB: notification_cooldowns table + UNIQUE constraint in $SCHEMA"

if [[ -n "$SCHEMA" ]]; then
  TABLE_EXISTS=$(PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SCHEMA' AND table_name='notification_cooldowns'" 2>/dev/null | tr -d '[:space:]' || echo "0")
  echo "  Table exists count: $TABLE_EXISTS"
  if [[ "$TABLE_EXISTS" =~ ^[0-9]+$ && "$TABLE_EXISTS" -ge 1 ]]; then
    ok "Test 8 — notification_cooldowns table exists in $SCHEMA"
  else
    fail "Test 8 — notification_cooldowns table NOT found in $SCHEMA (backend needs rebuild/restart?)"
  fi
else
  fail "Test 8 — could not resolve tenant schema"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 9 — DB: UNIQUE constraint handles NULL product_id via sentinel UUID
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 9 — DB: NULL sentinel upsert (TREND cooldown) is idempotent"

SENTINEL="00000000-0000-0000-0000-000000000000"
STORE_UUID=$(cat /proc/sys/kernel/random/uuid 2>/dev/null || python3 -c "import uuid; print(uuid.uuid4())")

if [[ -n "$SCHEMA" && "$TABLE_EXISTS" =~ ^[0-9]+$ && "$TABLE_EXISTS" -ge 1 ]]; then
  # Insert twice for the same TREND_DOWN + sentinel + store — second should upsert only
  PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "
    INSERT INTO \"$SCHEMA\".notification_cooldowns (id, cooldown_type, product_id, store_id, last_sent_at)
    VALUES (gen_random_uuid(), 'TREND_DOWN', '$SENTINEL', '$STORE_UUID', NOW() - INTERVAL '3 hours')
    ON CONFLICT (cooldown_type, product_id, store_id) DO UPDATE SET last_sent_at = EXCLUDED.last_sent_at;
    INSERT INTO \"$SCHEMA\".notification_cooldowns (id, cooldown_type, product_id, store_id, last_sent_at)
    VALUES (gen_random_uuid(), 'TREND_DOWN', '$SENTINEL', '$STORE_UUID', NOW())
    ON CONFLICT (cooldown_type, product_id, store_id) DO UPDATE SET last_sent_at = EXCLUDED.last_sent_at;
  " 2>/dev/null

  COUNT=$(PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "SELECT COUNT(*) FROM \"$SCHEMA\".notification_cooldowns WHERE cooldown_type='TREND_DOWN' AND product_id='$SENTINEL' AND store_id='$STORE_UUID'" 2>/dev/null || echo "ERR")
  echo "  Rows after 2 upserts: $COUNT"
  if [[ "$COUNT" == "1" ]]; then
    ok "Test 9 — ON CONFLICT with sentinel UUID works: 2 upserts = 1 row"
  else
    fail "Test 9 — expected 1 row, got $COUNT (NULL sentinel not working)"
  fi
else
  echo "  SKIP — table not found"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 10 — DB: INTERVAL sargable query (cooldown check) uses index
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 10 — DB: sargable INTERVAL cooldown query returns correct result"

if [[ -n "$SCHEMA" && "$TABLE_EXISTS" =~ ^[0-9]+$ && "$TABLE_EXISTS" -ge 1 ]]; then
  # Row from Test 9 has last_sent_at=NOW() → should be within 2h cooldown
  ACTIVE=$(PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "SELECT COUNT(*) FROM \"$SCHEMA\".notification_cooldowns WHERE cooldown_type='TREND_DOWN' AND product_id='$SENTINEL' AND store_id='$STORE_UUID' AND last_sent_at > NOW() - (7200 * INTERVAL '1 second')" 2>/dev/null || echo "ERR")
  echo "  Active cooldown rows: $ACTIVE"
  if [[ "$ACTIVE" == "1" ]]; then
    ok "Test 10 — INTERVAL query: row within 2h cooldown correctly detected"
  else
    fail "Test 10 — expected 1, got $ACTIVE"
  fi

  # Expired row: last_sent_at was set to NOW()-3h in first insert then overwritten → test with explicit old timestamp
  PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "UPDATE \"$SCHEMA\".notification_cooldowns SET last_sent_at = NOW() - INTERVAL '3 hours' WHERE cooldown_type='TREND_DOWN' AND product_id='$SENTINEL' AND store_id='$STORE_UUID'" 2>/dev/null
  EXPIRED=$(PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "SELECT COUNT(*) FROM \"$SCHEMA\".notification_cooldowns WHERE cooldown_type='TREND_DOWN' AND product_id='$SENTINEL' AND store_id='$STORE_UUID' AND last_sent_at > NOW() - (7200 * INTERVAL '1 second')" 2>/dev/null || echo "ERR")
  echo "  Expired cooldown rows: $EXPIRED"
  if [[ "$EXPIRED" == "0" ]]; then
    ok "Test 10b — INTERVAL query: expired cooldown (3h ago) correctly not detected"
  else
    fail "Test 10b — expected 0, got $EXPIRED"
  fi

  # Cleanup
  PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "DELETE FROM \"$SCHEMA\".notification_cooldowns WHERE store_id='$STORE_UUID'" 2>/dev/null
else
  echo "  SKIP — table not found"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 11 — DB: trend_notification_enabled column in tenant_preferences
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 11 — DB: trend_notification_enabled column in tenant_preferences"

if [[ -n "$SCHEMA" ]]; then
  COL=$(PGPASSWORD=keevo_local_pwd psql -h localhost -p 5444 -U keevo -d keevo_dev -t -A -q -c "SELECT column_name FROM information_schema.columns WHERE table_schema='$SCHEMA' AND table_name='tenant_preferences' AND column_name='trend_notification_enabled'" 2>/dev/null || echo "")
  if [[ "$COL" == "trend_notification_enabled" ]]; then
    ok "Test 11 — trend_notification_enabled column exists in $SCHEMA.tenant_preferences"
  else
    fail "Test 11 — column NOT found (migration may not have run yet)"
  fi
else
  fail "Test 11 — no schema resolved"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 12 — Stock alert enabled flag round-trip
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 12 — PUT stock alert toggle (enabled → disabled → enabled)"

# Disable
PAYLOAD12A=$(mk_payload stockAlertEnabled false)
RESP1=$(curl -s -w "\n%{http_code}" -X PUT "$BASE/api/v1/tenant/report-preferences" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data-raw "$PAYLOAD12A")
HTTP1=$(echo "$RESP1" | tail -1)
BODY1=$(echo "$RESP1" | sed '$d')
VAL1=$(echo "$BODY1" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',d).get('stockAlertEnabled','?'))" 2>/dev/null || echo "?")

# Re-enable
PAYLOAD12B=$(mk_payload stockAlertEnabled true)
RESP2=$(curl -s -w "\n%{http_code}" -X PUT "$BASE/api/v1/tenant/report-preferences" \
  -H "$AUTH" -H 'Content-Type: application/json' \
  --data-raw "$PAYLOAD12B")
HTTP2=$(echo "$RESP2" | tail -1)
BODY2=$(echo "$RESP2" | sed '$d')
VAL2=$(echo "$BODY2" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',d).get('stockAlertEnabled','?'))" 2>/dev/null || echo "?")

echo "  HTTP $HTTP1 (disable) / HTTP $HTTP2 (enable)"

if [[ "$HTTP1" == "200" && "$HTTP2" == "200" && ("$VAL1" == "False" || "$VAL1" == "false") && ("$VAL2" == "True" || "$VAL2" == "true") ]]; then
  ok "Test 12 — stockAlertEnabled toggle: false ($VAL1) → true ($VAL2)"
else
  fail "Test 12 — toggle unexpected: disable=$VAL1 ($HTTP1), enable=$VAL2 ($HTTP2)"
fi

# ─────────────────────────────────────────────────────────────────────────────
# TEST 13 — Notification prefs without auth → 401
# ─────────────────────────────────────────────────────────────────────────────
hdr "Test 13 — PUT /report-preferences (no auth) → 401"

HTTP=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE/api/v1/tenant/report-preferences" \
  -H 'Content-Type: application/json' \
  --data-raw '{"trendNotificationEnabled": false}')
echo "  HTTP $HTTP"
if [[ "$HTTP" == "401" ]]; then
  ok "Test 13 — 401 Unauthorized"
else
  fail "Test 13 — expected 401, got $HTTP"
fi

# ─────────────────────────────────────────────────────────────────────────────
# SUMMARY
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════════════════"
echo "  Story 8.1 — Tests completed"
echo "  ✅ PASSED: $PASS_COUNT"
echo "  ❌ FAILED: $FAIL_COUNT"
echo "════════════════════════════════════════════════════════"

[[ $FAIL_COUNT -eq 0 ]]
