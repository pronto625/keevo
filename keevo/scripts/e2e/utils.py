"""
Shared utilities for Keevo E2E test scripts.

Usage (from any test script):
    from utils import Api, Db, section, ok, fail, skip, Config
"""

import subprocess
import json
import sys
import os
import time
import argparse

# ── ANSI colours ──────────────────────────────────────────────────────────────

_NO_COLOR = os.environ.get("NO_COLOR") or not sys.stdout.isatty()
GREEN  = "" if _NO_COLOR else "\033[92m"
RED    = "" if _NO_COLOR else "\033[91m"
YELLOW = "" if _NO_COLOR else "\033[93m"
BLUE   = "" if _NO_COLOR else "\033[94m"
BOLD   = "" if _NO_COLOR else "\033[1m"
RESET  = "" if _NO_COLOR else "\033[0m"

# ── Config ────────────────────────────────────────────────────────────────────

class Config:
    """
    Runtime configuration resolved from:
      1. env vars (CI-friendly)
      2. argparse arguments
      3. hardcoded local-dev defaults
    """
    BASE_URL       = os.environ.get("KEEVO_BASE_URL",    "http://localhost:8080")
    DB_HOST        = os.environ.get("KEEVO_DB_HOST",     "localhost")
    DB_PORT        = os.environ.get("KEEVO_DB_PORT",     "5444")
    DB_NAME        = os.environ.get("KEEVO_DB_NAME",     "keevo_dev")
    DB_USER        = os.environ.get("KEEVO_DB_USER",     "keevo")
    DB_PASS        = os.environ.get("KEEVO_DB_PASS",     "keevo_local_pwd")
    DOCKER_PG      = os.environ.get("KEEVO_DOCKER_PG",   "keevo_postgres")
    VERBOSE        = os.environ.get("KEEVO_VERBOSE",     "false").lower() == "true"

    @classmethod
    def from_args(cls, args: argparse.Namespace):
        cls.BASE_URL  = args.base_url
        cls.VERBOSE   = args.verbose
        return cls


def default_arg_parser(description: str) -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=description)
    p.add_argument("--base-url",  default=Config.BASE_URL,
                   help="Backend base URL (default: %(default)s)")
    p.add_argument("--verbose",   action="store_true",
                   help="Print raw HTTP response bodies")
    return p


# ── Output helpers ────────────────────────────────────────────────────────────

_PASS = _FAIL = _SKIP = 0


def section(msg: str):
    print(f"\n{BOLD}{BLUE}{'═' * 58}{RESET}")
    print(f"{BOLD}{BLUE}  {msg}{RESET}")
    print(f"{BOLD}{BLUE}{'─' * 58}{RESET}")


def ok(msg: str):
    global _PASS
    _PASS += 1
    print(f"  {GREEN}✓{RESET}  {msg}")


def fail(msg: str, abort: bool = True):
    global _FAIL
    _FAIL += 1
    print(f"  {RED}✗  FAIL: {msg}{RESET}", file=sys.stderr)
    if abort:
        _print_summary()
        sys.exit(1)


def skip(msg: str):
    global _SKIP
    _SKIP += 1
    print(f"  {YELLOW}⊘  SKIP: {msg}{RESET}")


def _print_summary():
    total = _PASS + _FAIL + _SKIP
    color = GREEN if _FAIL == 0 else RED
    print(f"\n{BOLD}{'═' * 58}")
    print(f"{color}  {_PASS}/{total} assertions passées"
          + (f" — {_FAIL} échec(s)" if _FAIL else "")
          + (f" — {_SKIP} ignoré(s)" if _SKIP else "")
          + RESET)
    print(f"{BOLD}{'═' * 58}{RESET}")


def finish():
    """Call at the end of every test script to print summary and set exit code."""
    _print_summary()
    sys.exit(0 if _FAIL == 0 else 1)


# ── HTTP client (wraps curl) ──────────────────────────────────────────────────

class Api:
    """Thin curl wrapper. Returns (status_code: str, body: dict | str)."""

    SENTINEL = "|||KEEVO_STATUS|||"

    def __init__(self, base_url: str = None):
        self.base_url = (base_url or Config.BASE_URL).rstrip("/")

    def _request(self, method: str, path: str, body=None, token: str = None,
                 expected: int = None) -> tuple[str, dict | str]:
        url = f"{self.base_url}{path}"
        args = ["curl", "-s", "-w", f"{self.SENTINEL}%{{http_code}}", "-X", method, url]

        args += ["-H", "Content-Type: application/json"]
        if token:
            args += ["-H", f"Authorization: Bearer {token}"]
        if body:
            args += ["-d", json.dumps(body) if isinstance(body, dict) else body]

        result = subprocess.run(args, capture_output=True, text=True, timeout=30)
        raw = result.stdout
        idx = raw.rfind(self.SENTINEL)
        raw_body = raw[:idx].strip() if idx != -1 else raw.strip()
        code = raw.split(self.SENTINEL)[-1].strip() if idx != -1 else "???"

        if Config.VERBOSE:
            print(f"  {YELLOW}→ {method} {url}  HTTP {code}{RESET}")
            print(f"  {YELLOW}  {raw_body[:400]}{RESET}")

        try:
            parsed = json.loads(raw_body) if raw_body else {}
        except json.JSONDecodeError:
            parsed = raw_body

        if expected and code != str(expected):
            fail(f"{method} {path} — attendu HTTP {expected}, reçu {code}: {raw_body[:300]}")

        return code, parsed

    def post(self, path: str, body=None, token: str = None, expected: int = None):
        return self._request("POST", path, body=body, token=token, expected=expected)

    def get(self, path: str, token: str = None, expected: int = None):
        return self._request("GET", path, token=token, expected=expected)

    def put(self, path: str, body=None, token: str = None, expected: int = None):
        return self._request("PUT", path, body=body, token=token, expected=expected)

    def delete(self, path: str, token: str = None, expected: int = None):
        return self._request("DELETE", path, token=token, expected=expected)


# ── Database helpers (psql via docker exec or direct) ─────────────────────────

class Db:
    """PostgreSQL query helper. Uses direct psql if available, docker exec otherwise."""

    def __init__(self, schema: str = "public"):
        self.schema = schema

    def _psql(self, sql: str) -> str:
        """Execute SQL and return stdout. Tries docker exec first, then direct psql."""
        pg_env = {**os.environ,
                  "PGPASSWORD": Config.DB_PASS,
                  "PGSSLMODE": "disable"}

        # Try direct connection first (faster, no docker dependency)
        try:
            r = subprocess.run(
                ["psql", "-h", Config.DB_HOST, "-p", Config.DB_PORT,
                 "-U", Config.DB_USER, "-d", Config.DB_NAME,
                 "-t", "-A", "-c", sql],
                capture_output=True, text=True, timeout=10, env=pg_env)
            if r.returncode == 0:
                return r.stdout.strip()
        except FileNotFoundError:
            pass  # psql not installed locally — fall through to docker

        # Fallback: docker exec
        r = subprocess.run(
            ["docker", "exec", Config.DOCKER_PG,
             "psql", "-U", Config.DB_USER, "-d", Config.DB_NAME,
             "-t", "-A", "-c", sql],
            capture_output=True, text=True, timeout=10)
        return r.stdout.strip()

    def query(self, sql: str) -> list[str]:
        """Return rows as a list of strings."""
        raw = self._psql(sql)
        return [r for r in raw.split("\n") if r.strip()] if raw else []

    def scalar(self, sql: str) -> str:
        """Return first cell of first row."""
        rows = self.query(sql)
        return rows[0].split("|")[0].strip() if rows else ""

    def count(self, table: str, where: str = "") -> int:
        """Return row count for a (schema-qualified) table."""
        sql = f'SELECT COUNT(*) FROM "{self.schema}"."{table}"'
        if where:
            sql += f" WHERE {where}"
        val = self.scalar(sql + ";")
        try:
            return int(val)
        except ValueError:
            return -1

    def rows(self, table: str, cols: str = "*", where: str = "",
             order: str = "created_at") -> list[str]:
        """Return rows from a (schema-qualified) table."""
        sql = f'SELECT {cols} FROM "{self.schema}"."{table}"'
        if where:
            sql += f" WHERE {where}"
        if order:
            sql += f" ORDER BY {order}"
        return self.query(sql + ";")


# ── Wait for backend ──────────────────────────────────────────────────────────

def wait_for_backend(timeout: int = 30):
    """Block until /actuator/health returns 200, or abort after `timeout` seconds."""
    api = Api()
    deadline = time.time() + timeout
    print(f"  Attente backend ({Config.BASE_URL}) ", end="", flush=True)
    while time.time() < deadline:
        try:
            code, _ = api.get("/actuator/health")
            if code == "200":
                print(f" {GREEN}UP{RESET}")
                return
        except Exception:
            pass
        print(".", end="", flush=True)
        time.sleep(1)
    print()
    fail(f"Backend indisponible après {timeout}s — vérifier que le serveur est lancé")
