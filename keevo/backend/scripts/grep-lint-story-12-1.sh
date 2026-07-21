#!/usr/bin/env bash
# grep-lint-story-12-1.sh
# Story 12.1 — AC4: TDD grep lint for deploy-backend.yml JWT key permissions
#
# Asserts (post-review chgrp+640 strategy):
#   1. deploy-backend.yml contains `chmod 640 keys/private_key.pem` (private key
#      is group-readable for the container, NOT world-readable)
#   2. deploy-backend.yml does NOT contain `chmod 644 keys/private_key.pem`
#      (the old insecure private-key mode)
#   3. deploy-backend.yml contains `chgrp 1001 keys/private_key.pem` (the
#      container reads via group GID 1001, no root/chown required)
#   4. deploy-backend.yml does NOT contain `chown 1001:1001 keys/private_key.pem`
#      (the old strategy that fails under a non-root SSH deploy user)
#
# This script is designed to fail (RED) before the fix and pass (GREEN) after.
# Wired into backend-ci.yml. Run from the repository root.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
DEPLOY_FILE="${REPO_ROOT}/.github/workflows/deploy-backend.yml"

PASS=0
FAIL=0

ok()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
fail(){ echo "[FAIL] $1 — $2" >&2; FAIL=$((FAIL+1)); }

echo "=== Story 12.1 — AC4 grep lint: deploy-backend.yml JWT key permissions ==="
echo ""

# ── Test 1: deploy-backend.yml must contain chmod 640 for private key ─────────
if grep -qF 'chmod 640 keys/private_key.pem' "${DEPLOY_FILE}"; then
  ok "deploy-backend.yml contains 'chmod 640 keys/private_key.pem'"
else
  fail "deploy-backend.yml must contain 'chmod 640 keys/private_key.pem'" \
       "missing — private key is not restricted to group-only read"
fi

# ── Test 2: deploy-backend.yml must NOT contain chmod 644 for private key ─────
if grep -qF 'chmod 644 keys/private_key.pem' "${DEPLOY_FILE}"; then
  fail "deploy-backend.yml must NOT contain 'chmod 644 keys/private_key.pem'" \
       "private key is world-readable — old insecure mode still present"
else
  ok "deploy-backend.yml does NOT contain 'chmod 644 keys/private_key.pem'"
fi

# ── Test 3: deploy-backend.yml must contain chgrp 1001 for private key ────────
if grep -qF 'chgrp 1001 keys/private_key.pem' "${DEPLOY_FILE}"; then
  ok "deploy-backend.yml contains 'chgrp 1001 keys/private_key.pem'"
else
  fail "deploy-backend.yml must contain 'chgrp 1001 keys/private_key.pem'" \
       "container has no group-read channel — key unreadable for UID 1001"
fi

# ── Test 4: deploy-backend.yml must NOT contain the old chown 1001:1001 ───────
if grep -qF 'chown 1001:1001 keys/private_key.pem' "${DEPLOY_FILE}"; then
  fail "deploy-backend.yml must NOT contain 'chown 1001:1001 keys/private_key.pem'" \
       "old strategy present — fails under non-root SSH deploy user (EPERM)"
else
  ok "deploy-backend.yml does NOT contain 'chown 1001:1001 keys/private_key.pem'"
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo "=== Results: ${PASS} passed, ${FAIL} failed ==="

if [ "${FAIL}" -gt 0 ]; then
  exit 1
fi
exit 0
