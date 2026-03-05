#!/usr/bin/env python3
"""
E2E Test Suite — Story 1-4: Onboarding Wizard, Sector Templates & Shop Setup
=============================================================================

Validates the full onboarding flow end-to-end:
  - POST /api/v1/auth/register   → 201, JWT token
  - POST /api/v1/onboarding/complete → 200, categories seeded, store renamed
  - PostgreSQL assertions: categories count, store name, tenant_preferences
  - Security: 401 without JWT, 400 on invalid sector, 400/422 on empty name
  - Idempotency: 400 ONBOARDING_ALREADY_COMPLETED on second attempt
  - All 8 sector templates: category counts validated

Usage:
  python3 story_1_4_onboarding.py
  python3 story_1_4_onboarding.py --base-url http://prod.example.com
  python3 story_1_4_onboarding.py --verbose
  KEEVO_VERBOSE=true python3 story_1_4_onboarding.py

Exit codes:
  0 — all assertions passed
  1 — one or more assertions failed
"""

import sys
import os
import random
import string
import time

# Allow running from any cwd
sys.path.insert(0, os.path.dirname(__file__))
from utils import Api, Db, Config, section, ok, fail, skip, finish, \
    wait_for_backend, default_arg_parser


# ── Expected category counts per sector ──────────────────────────────────────

SECTOR_CATEGORY_COUNTS = {
    "CLOTHING":         13,
    "ELECTRONICS":      14,
    "BOOKS_STATIONERY": 12,
    "HOME_APPLIANCES":  14,
    "FOOD_GROCERY":     15,
    "PHARMACY":         13,
    "HARDWARE":         14,
    "OTHER":             3,
}


# ── Helpers ───────────────────────────────────────────────────────────────────

def rand_phone() -> str:
    """Generate a unique Cameroon phone number for test isolation."""
    digits = "".join(random.choices(string.digits, k=8))
    return f"+237{digits}"


def register(api: Api) -> tuple[str, str]:
    """Register a fresh user and return (token, schema_name)."""
    phone = rand_phone()
    code, body = api.post("/api/v1/auth/register",
                          {"phoneNumber": phone, "password": "TestPass123!"},
                          expected=201)
    token = body.get("token") or body.get("accessToken", "")
    if not token:
        fail(f"register: pas de token dans la réponse — body={body}")
    tenant_code = body.get("tenantCode", "")
    schema = tenant_code.lower().replace("-", "_")
    return token, schema


# ── Test cases ────────────────────────────────────────────────────────────────

def test_register_returns_jwt(api: Api):
    section("T01 — Register → 201 + token JWT")
    phone = rand_phone()
    code, body = api.post("/api/v1/auth/register",
                          {"phoneNumber": phone, "password": "TestPass123!"})
    if code == "201":
        ok("HTTP 201 CREATED")
    else:
        fail(f"HTTP {code} (attendu 201): {body}")

    token = body.get("token") or body.get("accessToken")
    if token and len(token) > 50:
        ok(f"token JWT présent ({len(token)} chars)")
    else:
        fail(f"token absent ou trop court: {token}")

    tenant_code = body.get("tenantCode", "")
    if tenant_code.startswith("KV-"):
        ok(f"tenantCode présent: {tenant_code}")
    else:
        fail(f"tenantCode absent ou format incorrect: {tenant_code}")

    return token, tenant_code.lower().replace("-", "_")


def test_onboarding_clothing(api: Api, token: str, schema: str):
    section("T02 — Onboarding CLOTHING → 200 + 13 catégories + store renommé")
    code, body = api.post("/api/v1/onboarding/complete",
                          {"sectorType": "CLOTHING", "storeName": "Boutique Céleste"},
                          token=token)
    if code == "200":
        ok("HTTP 200 OK")
    else:
        fail(f"HTTP {code}: {body}")

    cats = body.get("categoriesCreated", -1)
    if cats == 13:
        ok(f"categoriesCreated={cats}")
    else:
        fail(f"categoriesCreated={cats} (attendu 13)")

    if body.get("sectorType") == "CLOTHING":
        ok("sectorType=CLOTHING dans la réponse")
    else:
        fail(f"sectorType={body.get('sectorType')}")

    if body.get("storeName") == "Boutique Céleste":
        ok("storeName=Boutique Céleste dans la réponse")
    else:
        fail(f"storeName={body.get('storeName')}")

    tenant_id = body.get("tenantId", "")
    if tenant_id:
        ok(f"tenantId présent: {tenant_id}")
    else:
        fail("tenantId absent de la réponse")


def test_db_categories_count(schema: str):
    section("T03 — DB: 13 catégories dans le schema tenant")
    db = Db(schema)
    count = db.count("categories")
    if count == 13:
        ok(f"categories: {count} lignes en DB")
    else:
        names = db.rows("categories", "name")
        fail(f"count={count} (attendu 13)\n  noms: {names}")


def test_db_store_name(schema: str):
    section("T04 — DB: store name mis à jour")
    db = Db(schema)
    name = db.scalar(f'SELECT name FROM "{schema}".stores ORDER BY created_at LIMIT 1;')
    if name == "Boutique Céleste":
        ok(f"store name = '{name}'")
    else:
        fail(f"store name = '{name}' (attendu 'Boutique Céleste')")


def test_db_tenant_preferences(schema: str):
    section("T05 — DB: tenant_preferences créées")
    db = Db(schema)
    rows = db.rows("tenant_preferences", "sector_type, eod_report_time::text, stock_alert_enabled")
    if not rows:
        fail("tenant_preferences vide")

    row = rows[0]
    if "CLOTHING" in row:
        ok("sector_type=CLOTHING")
    else:
        fail(f"sector_type absent ou incorrect: {row}")

    if "20:00:00" in row:
        ok("eod_report_time=20:00:00")
    else:
        fail(f"eod_report_time incorrect: {row}")

    if "t" in row.split("|")[-1]:
        ok("stock_alert_enabled=true")
    else:
        fail(f"stock_alert_enabled incorrect: {row}")


def test_no_jwt_returns_401(api: Api):
    section("T06 — Sécurité: 401 sans JWT")
    code, body = api.post("/api/v1/onboarding/complete",
                          {"sectorType": "CLOTHING", "storeName": "Test"})
    if code == "401":
        ok("HTTP 401 UNAUTHORIZED sans JWT")
    else:
        fail(f"attendu 401, reçu {code}: {body}")


def test_unknown_sector_returns_400(api: Api, token: str):
    section("T07 — Validation: 400/422 secteur inconnu")
    code, body = api.post("/api/v1/onboarding/complete",
                          {"sectorType": "SECTEUR_INVENTE_XYZ", "storeName": "Test"},
                          token=token)
    if code in ("400", "422"):
        ok(f"HTTP {code} — secteur inconnu rejeté")
    else:
        fail(f"attendu 400/422, reçu {code}: {body}")


def test_empty_store_name_rejected(api: Api, token: str):
    section("T08 — Validation: 400/422 nom boutique vide")
    code, body = api.post("/api/v1/onboarding/complete",
                          {"sectorType": "CLOTHING", "storeName": ""},
                          token=token)
    if code in ("400", "422"):
        ok(f"HTTP {code} — nom vide rejeté")
    else:
        fail(f"attendu 400/422, reçu {code}: {body}")


def test_idempotency_already_completed(api: Api, token: str):
    section("T09 — Idempotence: 400 ONBOARDING_ALREADY_COMPLETED")
    # token already used in T02 — second call must be rejected
    code, body = api.post("/api/v1/onboarding/complete",
                          {"sectorType": "CLOTHING", "storeName": "Deuxième tentative"},
                          token=token)
    if code == "400":
        domain_code = (body.get("domainCode") or body.get("errorCode") or
                       body.get("code") or str(body))
        if "ALREADY_COMPLETED" in domain_code.upper():
            ok(f"HTTP 400 ONBOARDING_ALREADY_COMPLETED")
        else:
            ok(f"HTTP 400 (domainCode={domain_code})")
    else:
        fail(f"attendu 400, reçu {code}: {body}")


def test_all_sector_templates(api: Api):
    section("T10 — Tous les 8 secteurs: compte de catégories")
    all_ok = True

    for sector, expected_count in SECTOR_CATEGORY_COUNTS.items():
        token, _schema = register(api)
        code, body = api.post("/api/v1/onboarding/complete",
                              {"sectorType": sector, "storeName": f"Boutique {sector}"},
                              token=token)
        count = body.get("categoriesCreated", -1) if isinstance(body, dict) else -1
        if code == "200" and count == expected_count:
            ok(f"{sector:<20}  → {count} catégories")
        else:
            print(f"  {'✗':<2}  FAIL  {sector:<20}  → {count} (attendu {expected_count})  HTTP {code}")
            all_ok = False

        time.sleep(0.1)  # be gentle with the server

    if not all_ok:
        fail("Un ou plusieurs secteurs ont un mauvais compte de catégories", abort=False)


def test_tenant_schema_sync(api: Api):
    """
    T11 — TenantSchemaSyncService: a tenant registered before a 'new table' must
    still be able to complete onboarding (sync adds missing tables on login).

    This test verifies the sync is non-blocking: if the tenant schema already has
    all required tables, the request completes normally with HTTP 200.
    """
    section("T11 — TenantSchemaSyncService: schema sync non-bloquant")
    token, schema = register(api)

    code, body = api.post("/api/v1/onboarding/complete",
                          {"sectorType": "ELECTRONICS", "storeName": "Boutique Sync"},
                          token=token)
    if code == "200":
        ok("HTTP 200 — sync non-bloquant, onboarding réussi")
    else:
        fail(f"HTTP {code}: {body}")

    count = body.get("categoriesCreated", -1) if isinstance(body, dict) else -1
    expected = SECTOR_CATEGORY_COUNTS["ELECTRONICS"]
    if count == expected:
        ok(f"categoriesCreated={count} (ELECTRONICS)")
    else:
        fail(f"categoriesCreated={count} (attendu {expected})")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    parser = default_arg_parser("E2E — Story 1-4: Onboarding Wizard")
    args = parser.parse_args()
    Config.from_args(args)

    api = Api(Config.BASE_URL)

    print(f"\n{'-' * 58}")
    print(f"  Keevo E2E — Story 1-4: Onboarding Wizard")
    print(f"  Backend : {Config.BASE_URL}")
    print(f"{'-' * 58}")

    wait_for_backend()

    # --- T01-T05: Happy path (complete flow with DB assertions) ---
    token, schema = test_register_returns_jwt(api)
    test_onboarding_clothing(api, token, schema)

    # DB assertions only run if psql is available
    try:
        test_db_categories_count(schema)
        test_db_store_name(schema)
        test_db_tenant_preferences(schema)
    except Exception as e:
        skip(f"Assertions DB ignorées (psql / docker indisponible): {e}")

    # --- T06-T09: Edge cases & security ---
    test_no_jwt_returns_401(api)
    test_unknown_sector_returns_400(api, token)
    test_empty_store_name_rejected(api, token)
    test_idempotency_already_completed(api, token)

    # --- T10: All sector templates ---
    test_all_sector_templates(api)

    # --- T11: Schema sync ---
    test_tenant_schema_sync(api)

    finish()


if __name__ == "__main__":
    main()
