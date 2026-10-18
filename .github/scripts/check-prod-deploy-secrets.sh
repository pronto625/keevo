#!/usr/bin/env bash
# Pre-flight check of the production deploy settings — copied to the server by
# deploy-backend.yml and run there BEFORE any pull / restart, so a missing/weak value fails the
# deploy while the running stack keeps serving.
#
# Env: APP_DOMAIN (GitHub secret); ADMIN_DOMAIN, ADMIN_PHONE, ADMIN_PASSWORD (read by the
# workflow from the server's ~/keevo/.env).
#
# The ADMIN_PASSWORD rules mirror ProdSecretsValidator (keevo/backend, fail-fast at boot):
# not blank, not a known default (Admin@1234!…) nor a placeholder, >= 12 characters,
# 3 of 4 character classes (lower/upper/digit/symbol), <= 72 UTF-8 bytes (BCrypt limit).
# Checking here gives a clear message in the Actions log instead of a crash-looping backend.
# Values are NEVER printed — only the name of the offending secret.
set -euo pipefail
export LC_ALL=C.UTF-8

errors=0
fail() {
  echo "::error::$1 — see docs/MOP_MISE_A_JOUR_PROD.md §4"
  errors=1
}

for name in APP_DOMAIN ADMIN_DOMAIN ADMIN_PHONE ADMIN_PASSWORD; do
  if [ -z "${!name:-}" ]; then
    fail "Missing ${name} (APP_DOMAIN: GitHub secret; the others: ~/keevo/.env on the server)"
  fi
done

# ── ADMIN_DOMAIN: bare host name (no scheme, path or port), distinct from the API host ──
if [ -n "${ADMIN_DOMAIN:-}" ]; then
  if ! [[ "${ADMIN_DOMAIN}" =~ ^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$ ]]; then
    fail "ADMIN_DOMAIN must be a bare host name such as admin.keevo.cm (no https://, path or port)"
  elif app_domain="${APP_DOMAIN:-}"; [ "${ADMIN_DOMAIN,,}" = "${app_domain,,}" ]; then
    fail "ADMIN_DOMAIN must differ from APP_DOMAIN (the dashboard has its own host)"
  fi
fi

# ── ADMIN_PHONE: international number; the backend normalises to +<digits> ──
if [ -n "${ADMIN_PHONE:-}" ]; then
  phone="${ADMIN_PHONE//[[:space:].()-]/}"
  if ! [[ "${phone}" =~ ^\+?[1-9][0-9]{7,14}$ ]]; then
    fail "ADMIN_PHONE must be an international phone number such as +2376XXXXXXXX"
  fi
fi

# ── ADMIN_PASSWORD: same policy as ProdSecretsValidator ──
if [ -n "${ADMIN_PASSWORD:-}" ]; then
  pwd="${ADMIN_PASSWORD}"
  lower_pwd="${pwd,,}"
  trimmed="${pwd#"${pwd%%[![:space:]]*}"}"
  trimmed="${trimmed%"${trimmed##*[![:space:]]}"}"
  case "${trimmed,,}" in
    admin@1234!|keevo_local_pwd|change_me_in_dot_env|change_me_strong_password|ci_password|postgres|keevo|password)
      fail "ADMIN_PASSWORD is a known default value — generate a unique password" ;;
  esac
  for marker in change_me changeme your_ replace_with placeholder xxxxxxxx; do
    if [[ "${lower_pwd}" == *"${marker}"* ]]; then
      fail "ADMIN_PASSWORD contains a placeholder marker — set the real value"
      break
    fi
  done
  if [ "${#trimmed}" -lt 12 ]; then
    fail "ADMIN_PASSWORD is too short — at least 12 characters"
  fi
  bytes="$(printf '%s' "${pwd}" | wc -c)"
  if [ "${bytes}" -gt 72 ]; then
    fail "ADMIN_PASSWORD exceeds 72 UTF-8 bytes (BCrypt limit)"
  fi
  classes=0
  [[ "${pwd}" =~ [[:lower:]] ]] && classes=$((classes + 1))
  [[ "${pwd}" =~ [[:upper:]] ]] && classes=$((classes + 1))
  [[ "${pwd}" =~ [[:digit:]] ]] && classes=$((classes + 1))
  [[ "${pwd}" =~ [^[:alnum:][:space:]] ]] && classes=$((classes + 1))
  if [ "${classes}" -lt 3 ]; then
    fail "ADMIN_PASSWORD is too weak — use 3 of 4 classes (lowercase, uppercase, digit, symbol)"
  fi
fi

if [ "${errors}" -eq 0 ]; then
  echo "✓ APP_DOMAIN, ADMIN_DOMAIN, ADMIN_PHONE, ADMIN_PASSWORD present and valid"
fi
exit "${errors}"
