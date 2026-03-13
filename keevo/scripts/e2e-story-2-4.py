#!/usr/bin/env python3
"""E2E test for Story 2.4 — runs against http://localhost:8080"""

import subprocess, json, tempfile, os, time, random, sys

BASE = "http://localhost:8080"
PASS = 0
FAIL = 0

GREEN = "\033[0;32m"
RED   = "\033[0;31m"
BLUE  = "\033[0;34m"
NC    = "\033[0m"

def ok(msg):
    global PASS
    PASS += 1
    print(f"{GREEN}  ✓ {msg}{NC}")

def fail(msg):
    global FAIL
    FAIL += 1
    print(f"{RED}  ✗ {msg}{NC}")

def info(msg):
    print(f"  » {msg}")

def step(msg):
    print(f"\n{BLUE}--- {msg} ---{NC}")

def curl(*args):
    r = subprocess.run(["curl", "-s"] + list(args), capture_output=True, text=True, timeout=30)
    return r.stdout

def curl_w(*args):
    r = subprocess.run(["curl", "-s", "-w", "\n%{http_code}"] + list(args),
                       capture_output=True, text=True, timeout=30)
    lines = r.stdout.strip().split("\n")
    code  = lines[-1]
    body  = "\n".join(lines[:-1])
    return body, code

def db(sql):
    r = subprocess.run(
        ["docker", "exec", "keevo_postgres", "psql", "-U", "keevo", "-d", "keevo_dev", "-tAq", "-c", sql],
        capture_output=True, text=True, timeout=15
    )
    return r.stdout.strip()

def db_table(sql):
    r = subprocess.run(
        ["docker", "exec", "keevo_postgres", "psql", "-U", "keevo", "-d", "keevo_dev", "-P", "pager=off", "-c", sql],
        capture_output=True, text=True, timeout=15
    )
    print(r.stdout)

MAPPING = ('{"nameColumn":"nom","priceColumn":"prix_vente","buyPriceColumn":"prix_achat",'
           '"transportCostColumn":"cout_transport","quantityColumn":"quantite_initiale",'
           '"thresholdColumn":"seuil_min"}')

CSV_DATA = ("nom,prix_vente,prix_achat,cout_transport,quantite_initiale,seuil_min\n"
            "Produit Alpha,500,300,20,50,5\n"
            "Produit Beta,750,400,0,10,2\n"
            "Produit Gamma,1200,800,50,0,0\n")

# ─── SETUP ──────────────────────────────────────────────────────────────────
step("Register fresh OWNER + provision tenant")

phone = f"+226{random.randint(60000000, 79999999)}"
info(f"Phone: {phone}")

reg_body = curl("-X", "POST", f"{BASE}/api/v1/auth/register",
                "-H", "Content-Type: application/json",
                "-d", json.dumps({"phoneNumber": phone, "password": "Test1234!"}))
info(f"Register response: {reg_body[:150]}")
try:
    reg_d = json.loads(reg_body)
    if "tenantCode" in reg_d:
        ok(f"Registration OK (tenantCode={reg_d['tenantCode']})")
    else:
        fail(f"Registration failed: {reg_body[:200]}")
        sys.exit(1)
except Exception as e:
    fail(f"Registration parse error: {e} → {reg_body[:200]}")
    sys.exit(1)

# ─── LOGIN ─────────────────────────────────────────────────────────────────
step("Login OWNER (step 1)")

login_body = curl("-X", "POST", f"{BASE}/api/v1/auth/login",
                  "-H", "Content-Type: application/json",
                  "-d", json.dumps({"phoneNumber": phone, "password": "Test1234!"}))
login_d = json.loads(login_body)
lt = login_d["loginToken"]
tc = login_d["memberships"][0]["tenantCode"]
sn = login_d["memberships"][0]["schemaName"]
ok(f"Login token obtained, tenantCode={tc}, schema={sn}")

# ─── SELECT TENANT ─────────────────────────────────────────────────────────
step("Select tenant (step 2)")

st_body = curl("-X", "POST", f"{BASE}/api/v1/auth/select-tenant",
               "-H", "Content-Type: application/json",
               "-d", json.dumps({"loginToken": lt, "tenantCode": tc}))
st_d = json.loads(st_body)
AT = st_d["accessToken"]
ok(f"Access token obtained (len={len(AT)})")
SCHEMA = sn

# ─── AC1: Template ─────────────────────────────────────────────────────────
step("AC1 — CSV Template download")

tmpl_body, tmpl_code = curl_w("-H", f"Authorization: Bearer {AT}",
                               f"{BASE}/api/v1/products/import/template")
info(f"Template: {tmpl_body[:80]}")
if tmpl_code == "200":
    ok("Template returns 200")
else:
    fail(f"Template code expected=200 got={tmpl_code}")
if "nom" in tmpl_body:
    ok("Template contains 'nom' column")
else:
    fail("Template missing 'nom' column")
if "prix_vente" in tmpl_body:
    ok("Template contains 'prix_vente' column")
else:
    fail("Template missing 'prix_vente' column")

# ─── AC2: CSV Import ────────────────────────────────────────────────────────
step("AC2 — Import CSV (OWNER, valid 3-row CSV)")

with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False) as f:
    f.write(CSV_DATA)
    csv_path = f.name

imp_body, imp_code = curl_w("-H", f"Authorization: Bearer {AT}",
                             "-F", f"file=@{csv_path}",
                             "-F", f"mapping={MAPPING}",
                             f"{BASE}/api/v1/products/import")
info(f"Import: {imp_body}")
os.unlink(csv_path)

if imp_code == "207":
    ok("Import returns 207 Multi-Status")
else:
    fail(f"Import code expected=207 got={imp_code}")

try:
    imp_d = json.loads(imp_body)
    imported = imp_d["data"]["imported"]
    skipped  = imp_d["data"]["skipped"]
    if imported >= 3:
        ok(f"All 3 products imported (imported={imported})")
    else:
        fail(f"Expected >=3 imported, got {imported}")
    if skipped == 0:
        ok("0 rows skipped on first import")
    else:
        fail(f"Expected 0 skipped, got {skipped}")
except Exception as e:
    fail(f"Import response parse error: {e}")

# ─── DB verify products ────────────────────────────────────────────────────
step("DB verify — products + stock")

prod_count = db(f"SELECT COUNT(*) FROM \"{SCHEMA}\".products WHERE archived=false;")
if prod_count.isdigit() and int(prod_count) >= 3:
    ok(f"DB: ≥3 active products (count={prod_count})")
else:
    fail(f"DB: expected >=3 products, got '{prod_count}'")

stock_count = db(f"SELECT COUNT(*) FROM \"{SCHEMA}\".stock_levels;")
if stock_count.isdigit() and int(stock_count) >= 2:
    ok(f"DB: ≥2 stock_levels (count={stock_count})")
else:
    fail(f"DB: expected >=2 stock_levels, got '{stock_count}'")

# ─── AC8: Duplicate names skipped ──────────────────────────────────────────
step("AC8 — Re-import same CSV → duplicates skipped")

with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False) as f:
    f.write(CSV_DATA)
    csv_path2 = f.name

imp2_body, imp2_code = curl_w("-H", f"Authorization: Bearer {AT}",
                               "-F", f"file=@{csv_path2}",
                               "-F", f"mapping={MAPPING}",
                               f"{BASE}/api/v1/products/import")
info(f"Re-import: {imp2_body}")
os.unlink(csv_path2)

if imp2_code == "207":
    ok("Re-import returns 207")
else:
    fail(f"Re-import code expected=207 got={imp2_code}")

try:
    imp2_d = json.loads(imp2_body)
    if imp2_d["data"]["imported"] == 0:
        ok("0 new products on re-import")
    else:
        fail(f"Expected 0 imported on re-import, got {imp2_d['data']['imported']}")
    if imp2_d["data"]["skipped"] >= 3:
        ok(f"Re-import: ≥3 skipped as duplicates (skipped={imp2_d['data']['skipped']})")
    else:
        fail(f"Expected >=3 skipped, got {imp2_d['data']['skipped']}")
except Exception as e:
    fail(f"Re-import parse error: {e}")

# ─── AC3: Unauthenticated access ────────────────────────────────────────────
step("AC3 — Unauthenticated import rejected")

with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False) as f:
    f.write(CSV_DATA)
    csv_path3 = f.name

_, unauth_code = curl_w("-F", f"file=@{csv_path3}", "-F", f"mapping={MAPPING}",
                        f"{BASE}/api/v1/products/import")
os.unlink(csv_path3)

if unauth_code in ("401", "403"):
    ok(f"Unauthenticated import rejected with HTTP {unauth_code}")
else:
    fail(f"Expected 401/403 for unauthenticated import, got {unauth_code}")

# ─── AC5: Create DRAFT ──────────────────────────────────────────────────────
step("AC5 — Create DRAFT product")

draft_body, draft_code = curl_w("-H", f"Authorization: Bearer {AT}",
                                 "-H", "Content-Type: application/json",
                                 "-d", json.dumps({"name": "Brouillon Test Story24",
                                                   "price": 300, "buyPrice": 200}),
                                 f"{BASE}/api/v1/products/draft")
info(f"Draft: {draft_body}")
if draft_code == "201":
    ok("Create draft returns 201")
else:
    fail(f"Draft code expected=201 got={draft_code}")

try:
    draft_d = json.loads(draft_body)
    status = draft_d["data"]["status"]
    DRAFT_ID = draft_d["data"]["id"]
    if status == "DRAFT":
        ok("Created product has status=DRAFT")
    else:
        fail(f"Expected status=DRAFT, got {status}")
    ok(f"Draft ID: {DRAFT_ID}")
except Exception as e:
    fail(f"Draft parse error: {e}")
    DRAFT_ID = None

# ─── DB verify DRAFT ────────────────────────────────────────────────────────
step("DB verify — DRAFT status + notification")

time.sleep(1)  # wait for async notification listener

if DRAFT_ID:
    db_status = db(f"SELECT status FROM \"{SCHEMA}\".products WHERE id='{DRAFT_ID}';")
    if db_status == "DRAFT":
        ok("DB: product status=DRAFT")
    else:
        fail(f"DB: expected DRAFT, got '{db_status}'")

    notif_count = db(f"SELECT COUNT(*) FROM \"{SCHEMA}\".draft_notifications WHERE product_id='{DRAFT_ID}';")
    if notif_count.isdigit() and int(notif_count) >= 1:
        ok(f"DB: draft_notification created (count={notif_count})")
    else:
        fail(f"DB: expected >=1 draft_notification, got '{notif_count}'")
else:
    fail("Cannot verify DB — DRAFT_ID not available")

# ─── AC6: Drafts count ──────────────────────────────────────────────────────
step("AC6 — GET /api/v1/products/drafts/count")

cnt_body, cnt_code = curl_w("-H", f"Authorization: Bearer {AT}",
                             f"{BASE}/api/v1/products/drafts/count")
info(f"Count: {cnt_body}")
if cnt_code == "200":
    ok("Drafts count returns 200")
else:
    fail(f"Count code expected=200 got={cnt_code}")

try:
    cnt_d = json.loads(cnt_body)
    BEFORE_COUNT = cnt_d["data"]
    if BEFORE_COUNT >= 1:
        ok(f"Pending drafts count ≥1 (count={BEFORE_COUNT})")
    else:
        fail(f"Expected >=1 draft, got {BEFORE_COUNT}")
except Exception as e:
    fail(f"Count parse error: {e}")
    BEFORE_COUNT = 0

# ─── AC6: Promote DRAFT → ACTIVE ────────────────────────────────────────────
step("AC6 — Promote DRAFT → ACTIVE")

if DRAFT_ID:
    promo_body, promo_code = curl_w("-X", "PATCH",
                                    f"{BASE}/api/v1/products/{DRAFT_ID}",
                                    "-H", f"Authorization: Bearer {AT}",
                                    "-H", "Content-Type: application/json",
                                    "-d", json.dumps({"name": "Brouillon Test Story24",
                                                      "price": 300}))
    info(f"Promote: {promo_body[:200]}")
    if promo_code == "200":
        ok("Promote draft returns 200")
    else:
        fail(f"Promote code expected=200 got={promo_code}")

    try:
        promo_d = json.loads(promo_body)
        promo_status = promo_d["data"]["status"]
        if promo_status == "ACTIVE":
            ok("Promoted product status=ACTIVE")
        else:
            fail(f"Expected status=ACTIVE after promotion, got {promo_status}")
    except Exception as e:
        fail(f"Promote parse error: {e}")
else:
    fail("Cannot promote — DRAFT_ID not available")

# ─── DB verify ACTIVE + notification acknowledged ───────────────────────────
step("DB verify — ACTIVE status + notification acknowledged")

if DRAFT_ID:
    db_active = db(f"SELECT status FROM \"{SCHEMA}\".products WHERE id='{DRAFT_ID}';")
    if db_active == "ACTIVE":
        ok("DB: product status=ACTIVE after promotion")
    else:
        fail(f"DB: expected ACTIVE, got '{db_active}'")

    db_ack = db(f"SELECT acknowledged FROM \"{SCHEMA}\".draft_notifications WHERE product_id='{DRAFT_ID}';")
    if db_ack == "t":
        ok("DB: draft_notification acknowledged=true")
    else:
        fail(f"DB: expected acknowledged=t, got '{db_ack}'")
else:
    fail("Cannot verify DB — DRAFT_ID not available")

# ─── Final DB summary ────────────────────────────────────────────────────────
step(f"Final DB summary — schema: {SCHEMA}")

print("\n  === Products ===")
db_table(f"SELECT name, status, price FROM \"{SCHEMA}\".products WHERE archived=false ORDER BY created_at;")

print("  === Stock levels ===")
db_table(f"SELECT p.name, sl.quantity FROM \"{SCHEMA}\".stock_levels sl JOIN \"{SCHEMA}\".products p ON sl.product_id = p.id;")

print("  === Draft notifications ===")
db_table(f"SELECT product_name, acknowledged, created_at FROM \"{SCHEMA}\".draft_notifications ORDER BY created_at;")

# ─── Summary ────────────────────────────────────────────────────────────────
print("\n" + "=" * 55)
print("  Story 2.4 E2E Results")
print("=" * 55)
print(f"  {GREEN}PASS: {PASS}{NC}   {RED}FAIL: {FAIL}{NC}   Total: {PASS + FAIL}")
print()
if FAIL == 0:
    print(f"  {GREEN}✓ ALL TESTS PASSED — Story 2.4 validated{NC}")
    sys.exit(0)
else:
    print(f"  {RED}✗ {FAIL} TEST(S) FAILED{NC}")
    sys.exit(1)
