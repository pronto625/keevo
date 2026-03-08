#!/usr/bin/env python3
"""
Story 1.8 — E2E Integration Tests (Python)
===========================================
Validates: AC1 (audit entries written on events), AC2 (immutable — 403 on DELETE/PUT),
           AC3 (optional filter params), AC3-bis (tenant-scoped writes via TenantContext),
           AC4 (tenant data isolation), AC5 (French error messages, no stacktrace),
           AC6 (data returned from backend, queryable by entityType+entityId)

Design: each test section is FULLY self-contained.
  Every section registers a fresh user and replays the complete
  register → POST /auth/login → POST /auth/select-tenant flow
  before testing its specific feature.
  No section shares a JWT or phone number with any other section.

Run:   python3 e2e-story-1-8.py
Requires: backend running on localhost:8080, requests installed
          (pip install requests)
"""

import argparse
import requests
import sys
import uuid
import time

_parser = argparse.ArgumentParser(description="Story 1.8 E2E tests")
_parser.add_argument("--base-url", default="http://localhost:8080",
                     help="Backend base URL (default: http://localhost:8080)")
_args = _parser.parse_args()

BASE   = _args.base_url.rstrip("/")
PASS   = 0
FAILED = []


def check(label: str, condition: bool, detail: str = ""):
    global PASS
    if condition:
        print(f"  ✅  {label}")
        PASS += 1
    else:
        msg = f"  ❌  {label}"
        if detail:
            msg += f"\n      → {detail}"
        print(msg)
        FAILED.append(label)


def section(title: str):
    print(f"\n{'─' * 62}")
    print(f"  {title}")
    print(f"{'─' * 62}")


def fresh_phone() -> str:
    """Generate a unique phone number for each test."""
    ts = str(int(time.time() * 1000))[-8:]
    return f"+237600{ts}"


def full_login(phone: str, password: str = "Test1234!") -> tuple[str, str, str]:
    """
    Complete two-step login flow.
    Returns: (access_token, user_id, schema_name)
    """
    # Step 1: register
    r = requests.post(f"{BASE}/api/v1/auth/register",
                      json={"phoneNumber": phone, "password": password})
    assert r.status_code == 201, f"Register failed: {r.status_code} {r.text}"
    body = r.json()
    user_id = body.get("userId", "")
    tenant_code = body.get("tenantCode", "")
    assert tenant_code, f"No tenantCode in register response: {body}"

    # Step 2: login
    r = requests.post(f"{BASE}/api/v1/auth/login",
                      json={"phoneNumber": phone, "password": password})
    assert r.status_code == 200, f"Login failed: {r.status_code} {r.text}"
    login_token = r.json().get("loginToken", "")
    assert login_token, f"No loginToken: {r.json()}"

    # Step 3: select tenant
    r = requests.post(f"{BASE}/api/v1/auth/select-tenant",
                      json={"loginToken": login_token, "tenantCode": tenant_code})
    assert r.status_code == 200, f"SelectTenant failed: {r.status_code} {r.text}"
    body = r.json()
    access_token = body.get("accessToken", "")
    schema_name  = body.get("tenantId", "")
    assert access_token, f"No accessToken: {body}"

    return access_token, user_id, schema_name


# ── T01 ───────────────────────────────────────────────────────────────────────
section("T01 — Register → select-tenant → GET /audit?entityType=User&entityId → ≥1 USER_REGISTERED entry")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User", "entityId": user_id},
                     headers={"Authorization": f"Bearer {jwt}"})
    check("GET /audit?entityType=User&entityId={userId} returns 200", r.status_code == 200,
          f"status={r.status_code} body={r.text[:200]}")
    data = r.json().get("data", [])
    check("Response contains ≥1 audit entry", len(data) >= 1,
          f"data length: {len(data)}")
    actions = [e.get("action") for e in data]
    check("List contains USER_REGISTERED action (sorted DESC: may not be first)",
          "USER_REGISTERED" in actions,
          f"actions={actions}")
except Exception as e:
    check("T01 setup failed", False, str(e))

# ── T02 ───────────────────────────────────────────────────────────────────────
section("T02 — USER_REGISTERED entry has required fields (userId, entityType, action, occurredAt)")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User", "entityId": user_id},
                     headers={"Authorization": f"Bearer {jwt}"})
    data = r.json().get("data", [])
    # Find USER_REGISTERED entry specifically (entries are DESC — USER_AUTHENTICATED may be first)
    reg_entries = [e for e in data if e.get("action") == "USER_REGISTERED"]
    entry = reg_entries[0] if reg_entries else (data[0] if data else {})

    check("Entry has 'id' field (UUID)", bool(entry.get("id")),
          f"id={entry.get('id')}")
    check("Entry 'entityType' = 'User'", entry.get("entityType") == "User",
          f"entityType={entry.get('entityType')}")
    check("Entry 'action' = 'USER_REGISTERED'", entry.get("action") == "USER_REGISTERED",
          f"action={entry.get('action')} (available actions: {[e.get('action') for e in data]})")
    check("Entry 'userId' is non-null", bool(entry.get("userId")),
          f"userId={entry.get('userId')}")
    check("Entry 'occurredAt' is non-null (ISO format)",
          bool(entry.get("occurredAt")) and "T" in str(entry.get("occurredAt", "")),
          f"occurredAt={entry.get('occurredAt')}")
    check("Entry 'valueBefore' is null for creation event", entry.get("valueBefore") is None,
          f"valueBefore={entry.get('valueBefore')}")
except Exception as e:
    check("T02 setup failed", False, str(e))

# ── T03 ───────────────────────────────────────────────────────────────────────
section("T03 — DELETE /api/v1/audit/{id} → 403 AUDIT_IMMUTABLE")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    # Get audit entry id
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User", "entityId": user_id},
                     headers={"Authorization": f"Bearer {jwt}"})
    data = r.json().get("data", [])
    entry_id = data[0].get("id") if data else str(uuid.uuid4())

    r = requests.delete(f"{BASE}/api/v1/audit/{entry_id}",
                        headers={"Authorization": f"Bearer {jwt}"})
    check("DELETE /api/v1/audit/{id} returns HTTP 403", r.status_code == 403,
          f"status={r.status_code} body={r.text[:200]}")
    check("Response body has domainCode=AUDIT_IMMUTABLE",
          r.json().get("domainCode") == "AUDIT_IMMUTABLE",
          f"domainCode={r.json().get('domainCode')} body={r.text[:200]}")
except Exception as e:
    check("T03 setup failed", False, str(e))

# ── T04 ───────────────────────────────────────────────────────────────────────
section("T04 — PUT /api/v1/audit/{id} with body → 403 AUDIT_IMMUTABLE")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User", "entityId": user_id},
                     headers={"Authorization": f"Bearer {jwt}"})
    data = r.json().get("data", [])
    entry_id = data[0].get("id") if data else str(uuid.uuid4())

    r = requests.put(f"{BASE}/api/v1/audit/{entry_id}",
                     json={"action": "HACKED"},
                     headers={"Authorization": f"Bearer {jwt}"})
    check("PUT /api/v1/audit/{id} returns HTTP 403", r.status_code == 403,
          f"status={r.status_code} body={r.text[:200]}")
    check("Response body has domainCode=AUDIT_IMMUTABLE",
          r.json().get("domainCode") == "AUDIT_IMMUTABLE",
          f"domainCode={r.json().get('domainCode')}")
except Exception as e:
    check("T04 setup failed", False, str(e))

# ── T05 ───────────────────────────────────────────────────────────────────────
section("T05 — GET /audit?entityType=Product&entityId={nonexistent} → 200 with empty list")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "Product", "entityId": str(uuid.uuid4())},
                     headers={"Authorization": f"Bearer {jwt}"})
    check("GET /api/v1/audit with unknown entityId returns 200 (not 404/400)",
          r.status_code == 200,
          f"status={r.status_code} body={r.text[:200]}")
    data = r.json().get("data", [])
    check("Response data is empty array [] for unknown entityId", data == [],
          f"data={data}")
except Exception as e:
    check("T05 setup failed", False, str(e))

# ── T06 ───────────────────────────────────────────────────────────────────────
section("T06 — GET /api/v1/audit without JWT → 401")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User", "entityId": user_id})
    check("GET /api/v1/audit without JWT returns 401", r.status_code == 401,
          f"status={r.status_code} body={r.text[:200]}")
except Exception as e:
    check("T06 setup failed", False, str(e))

# ── T07 ───────────────────────────────────────────────────────────────────────
section("T07 — Entries sorted by occurredAt DESC")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    # After register + select-tenant, there should be at least 2 entries (USER_REGISTERED + USER_AUTHENTICATED)
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User"},
                     headers={"Authorization": f"Bearer {jwt}"})
    data = r.json().get("data", [])
    if len(data) >= 2:
        dates = [e.get("occurredAt", "") for e in data]
        sorted_desc = sorted(dates, reverse=True)
        check("Entries are sorted occurredAt DESC", dates == sorted_desc,
              f"dates={dates[:3]}")
    else:
        check("At least 1 entry returned for entityType=User filter (sort check skipped — only 1 entry)",
              len(data) >= 1,
              f"entries={len(data)}")
except Exception as e:
    check("T07 setup failed", False, str(e))

# ── T08 ───────────────────────────────────────────────────────────────────────
section("T08 — Tenant A cannot see Tenant B's entries (cross-tenant isolation)")

try:
    jwt_a, user_id_a, schema_a = full_login(fresh_phone())
    jwt_b, user_id_b, schema_b = full_login(fresh_phone())

    # Tenant A queries full log
    r_a = requests.get(f"{BASE}/api/v1/audit",
                       headers={"Authorization": f"Bearer {jwt_a}"})
    data_a = r_a.json().get("data", [])
    user_ids_a = {e.get("userId") for e in data_a}

    check("Tenant A: GET /audit returns 200", r_a.status_code == 200,
          f"status={r_a.status_code}")
    check("Tenant A's full log does NOT contain Tenant B's userId",
          user_id_b not in user_ids_a,
          f"Tenant B userId {user_id_b} found in Tenant A's log!")

    # Tenant B queries full log
    r_b = requests.get(f"{BASE}/api/v1/audit",
                       headers={"Authorization": f"Bearer {jwt_b}"})
    data_b = r_b.json().get("data", [])
    user_ids_b = {e.get("userId") for e in data_b}

    check("Tenant B: GET /audit returns 200", r_b.status_code == 200,
          f"status={r_b.status_code}")
    check("Tenant B's full log does NOT contain Tenant A's userId",
          user_id_a not in user_ids_b,
          f"Tenant A userId {user_id_a} found in Tenant B's log!")
except Exception as e:
    check("T08 setup failed", False, str(e))

# ── T09 ───────────────────────────────────────────────────────────────────────
section("T09 — GET /api/v1/audit (no params) → 200 with full tenant log ≥1 entry (NOT 400)")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     headers={"Authorization": f"Bearer {jwt}"})
    check("GET /api/v1/audit with zero params returns 200 (not 400)", r.status_code == 200,
          f"status={r.status_code} body={r.text[:200]}")
    data = r.json().get("data", [])
    check("Full tenant log has ≥1 entry", len(data) >= 1,
          f"data length: {len(data)}")
except Exception as e:
    check("T09 setup failed", False, str(e))

# ── T10 ───────────────────────────────────────────────────────────────────────
section("T10 — GET /api/v1/audit?entityType=User (no entityId) → 200 with filtered list")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User"},
                     headers={"Authorization": f"Bearer {jwt}"})
    check("GET /api/v1/audit?entityType=User returns 200", r.status_code == 200,
          f"status={r.status_code}")
    data = r.json().get("data", [])
    wrong_type_entries = [e for e in data if e.get("entityType") != "User"]
    check("All returned entries have entityType=User",
          len(wrong_type_entries) == 0,
          f"Wrong-type entries: {wrong_type_entries}")
except Exception as e:
    check("T10 setup failed", False, str(e))

# ── T11 ───────────────────────────────────────────────────────────────────────
section("T11 — 403 error field is in French (contains 'journal' or 'modifi')")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    r = requests.get(f"{BASE}/api/v1/audit",
                     params={"entityType": "User", "entityId": user_id},
                     headers={"Authorization": f"Bearer {jwt}"})
    data = r.json().get("data", [])
    entry_id = data[0].get("id") if data else str(uuid.uuid4())

    r = requests.delete(f"{BASE}/api/v1/audit/{entry_id}",
                        headers={"Authorization": f"Bearer {jwt}"})
    error_msg = r.json().get("error", "")
    check("403 error field is in French (contains 'journal' or 'modifi')",
          "journal" in error_msg.lower() or "modifi" in error_msg.lower(),
          f"error='{error_msg}'")
except Exception as e:
    check("T11 setup failed", False, str(e))

# ── T12 ───────────────────────────────────────────────────────────────────────
section("T12 — Error response body has NO stackTrace, exception, or trace field")

try:
    jwt, user_id, schema = full_login(fresh_phone())
    # Trigger a 403 response
    r = requests.delete(f"{BASE}/api/v1/audit/{uuid.uuid4()}",
                        headers={"Authorization": f"Bearer {jwt}"})
    body = r.json()
    forbidden_keys = {"stackTrace", "exception", "trace", "stack_trace"}
    exposed_keys = [k for k in body.keys() if k in forbidden_keys]
    check("Error response has NO stackTrace/exception/trace field",
          len(exposed_keys) == 0,
          f"Exposed security-sensitive keys: {exposed_keys}")
except Exception as e:
    check("T12 setup failed", False, str(e))

# ── T13 ───────────────────────────────────────────────────────────────────────
section("T13 — Tenant A full log (no params) returns 0 entries from Tenant B schemas")

try:
    jwt_a, user_id_a, schema_a = full_login(fresh_phone())
    jwt_b, user_id_b, schema_b = full_login(fresh_phone())

    # Tenant A full log
    r_a = requests.get(f"{BASE}/api/v1/audit",
                       headers={"Authorization": f"Bearer {jwt_a}"})
    data_a = r_a.json().get("data", [])
    b_entries_in_a = [e for e in data_a if e.get("userId") == user_id_b]
    check("Tenant A full log (no params): zero entries from Tenant B",
          len(b_entries_in_a) == 0,
          f"Found {len(b_entries_in_a)} Tenant B entries in Tenant A's log")

    # Tenant B full log
    r_b = requests.get(f"{BASE}/api/v1/audit",
                       headers={"Authorization": f"Bearer {jwt_b}"})
    data_b = r_b.json().get("data", [])
    a_entries_in_b = [e for e in data_b if e.get("userId") == user_id_a]
    check("Tenant B full log (no params): zero entries from Tenant A",
          len(a_entries_in_b) == 0,
          f"Found {len(a_entries_in_b)} Tenant A entries in Tenant B's log")
except Exception as e:
    check("T13 setup failed", False, str(e))

# ── Summary ───────────────────────────────────────────────────────────────────
total = PASS + len(FAILED)
print(f"\n{'═' * 62}")
print(f"  Story 1.8 E2E Results: {PASS}/{total} tests passed")
if FAILED:
    print(f"\n  FAILED ({len(FAILED)}):")
    for f in FAILED:
        print(f"    ✗ {f}")
    print(f"\n  Status: ❌ {len(FAILED)} test(s) failed")
    sys.exit(1)
else:
    print(f"\n  Status: ✅ ALL {PASS} TESTS PASSED — story 1.8 eligible for review")
print(f"{'═' * 62}\n")
