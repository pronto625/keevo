#!/usr/bin/env bash
# ============================================================================
# Story 10-7 — tests of the backup failure alert (run by backend-ci.yml)
# ============================================================================
# - backup-alert.sh with a stubbed curl: exit codes, JSON payload validity (CR, tab,
#   quote, other control characters; jq and built-in escaper), secrets never on argv.
# - Backup units rendered with render-backup-units.sh (the deploy path): OnFailure= target
#   is the shipped alert unit, EnvironmentFile=- keeps its dash, TimeoutStartSec present,
#   special characters of the deploy directory survive the sed.
# Usage: bash keevo/backend/scripts/tests/backup-alert-test.sh
# ============================================================================
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ALERT="${SCRIPTS_DIR}/backup-alert.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; failures=$((failures + 1)); }
check() {  # check <ok-message> <ko-message> <command…>
  local ok="$1" ko="$2"; shift 2
  if "$@"; then pass "${ok}"; else fail "${ko}"; fi
}

# ─── curl stub ───────────────────────────────────────────────────────────────
# Records argv, the config read on stdin and the --data-binary @file payload.
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/curl" << 'STUB'
#!/usr/bin/env bash
printf '%s\n' "$@" > "${STUB_DIR}/argv"
cat > "${STUB_DIR}/config"
prev=""
for a in "$@"; do
  if [[ "${prev}" == "--data-binary" ]]; then cp "${a#@}" "${STUB_DIR}/payload"; fi
  prev="${a}"
done
exit "${STUB_CURL_EXIT:-0}"
STUB
chmod +x "${WORK}/bin/curl"
# systemctl may be missing on CI runners: stub it too (Result lookup only).
printf '#!/usr/bin/env bash\necho exit-code\n' > "${WORK}/bin/systemctl"
chmod +x "${WORK}/bin/systemctl"

run_alert() {  # run_alert <unit-arg> [VAR=value…] — sets $rc
  local unit="$1"; shift
  rm -f "${WORK}/argv" "${WORK}/config" "${WORK}/payload"
  set +e
  env -i PATH="${WORK}/bin:/usr/bin:/bin" STUB_DIR="${WORK}" "$@" bash "${ALERT}" "${unit}" > /dev/null 2>&1
  rc=$?
  set -e
}

echo "backup-alert.sh — exit codes"
run_alert keevo-backup.service
check "no webhook URL → exit 2" "no webhook URL → exit ${rc}, expected 2" test "${rc}" -eq 2
run_alert keevo-backup.service BACKUP_ALERT_WEBHOOK_URL=https://hooks.example.invalid/x
check "webhook URL → exit 0" "webhook URL → exit ${rc}, expected 0" test "${rc}" -eq 0
run_alert keevo-backup.service BACKUP_ALERT_WEBHOOK_URL=https://hooks.example.invalid/x STUB_CURL_EXIT=22
check "curl failure → exit 1" "curl failure → exit ${rc}, expected 1" test "${rc}" -eq 1

echo "backup-alert.sh — payload is valid JSON (CR, tab, quote, control chars)"
NASTY=$'unit"with\rcr\ttab\\back\x01ctl\nnl.service'
for mode in jq builtin; do
  extra=()
  if [[ "${mode}" == "builtin" ]]; then
    extra=(BACKUP_ALERT_FORCE_BUILTIN_JSON=1)
  elif ! command -v jq > /dev/null 2>&1; then
    echo "  - jq not installed, jq path skipped"; continue
  fi
  run_alert "${NASTY}" BACKUP_ALERT_WEBHOOK_URL=https://hooks.example.invalid/x \
    BACKUP_ALERT_TO=$'+237\t"600' "${extra[@]}"
  if [[ ${rc} -eq 0 ]] && python3 -m json.tool "${WORK}/payload" > /dev/null 2>&1; then
    pass "${mode}: valid JSON"
  else
    fail "${mode}: invalid JSON (exit ${rc}): $(cat "${WORK}/payload" 2>/dev/null)"
  fi
  if python3 - "${WORK}/payload" "${NASTY}" << 'PY'
import json, sys
p = json.load(open(sys.argv[1]))
assert sys.argv[2] in p["text"], "unit name not preserved"
assert p["text"] == p["content"]
assert p["to"] == '+237\t"600'
PY
  then pass "${mode}: text/content/to preserved"; else fail "${mode}: payload content"; fi
done

echo "backup-alert.sh — secrets never on curl argv"
run_alert keevo-backup.service BACKUP_ALERT_WEBHOOK_URL=https://hooks.example.invalid/SECRETPATH \
  BACKUP_ALERT_WEBHOOK_TOKEN=SECRETTOKEN
if grep -q "SECRET" "${WORK}/argv"; then fail "URL or token on argv"; else pass "URL and token not on argv"; fi
if grep -q 'Authorization: Bearer SECRETTOKEN' "${WORK}/config" && grep -q 'SECRETPATH' "${WORK}/config"; then
  pass "URL and Authorization header passed through the config on stdin"
else
  fail "config on stdin missing URL or Authorization header"
fi

echo "render-backup-units.sh — rendered units"
HOME_DIR='/home/de&p#l\oy/keevo'
"${SCRIPTS_DIR}/render-backup-units.sh" "${HOME_DIR}" "${SCRIPTS_DIR}" "${WORK}/units"
SVC="${WORK}/units/keevo-backup.service"
if grep -q "__KEEVO_HOME__" "${WORK}/units/"*; then fail "placeholder left"; else pass "no placeholder left"; fi
check "deploy dir with &, #, \\ rendered literally" "ExecStart path mangled: $(grep ExecStart "${SVC}")" \
  grep -qxF "ExecStart=${HOME_DIR}/scripts/backup-db.sh" "${SVC}"
check "EnvironmentFile=- keeps its dash" "EnvironmentFile without '-'" \
  grep -qxF "EnvironmentFile=-${HOME_DIR}/.env.backup" "${SVC}"
check "TimeoutStartSec present" "TimeoutStartSec missing" grep -qE '^TimeoutStartSec=' "${SVC}"
target="$(sed -n 's/^OnFailure=//p' "${SVC}")"            # keevo-backup-alert@%n.service
template="${target/@%n/@}"                                # keevo-backup-alert@.service
if [[ -n "${target}" && -f "${SCRIPTS_DIR}/${template}" && -f "${WORK}/units/${template}" ]]; then
  pass "OnFailure=${target} → shipped unit ${template}"
else
  fail "OnFailure target '${target}' has no shipped unit file '${template}'"
fi
check "alert unit runs backup-alert.sh %i" "alert unit ExecStart" \
  grep -qxF "ExecStart=${HOME_DIR}/scripts/backup-alert.sh %i" "${WORK}/units/keevo-backup-alert@.service"

if [[ ${failures} -ne 0 ]]; then
  echo "❌ ${failures} check(s) failed" >&2
  exit 1
fi
echo "✓ all backup alert checks passed"
