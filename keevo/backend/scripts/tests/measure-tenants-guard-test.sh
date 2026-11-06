#!/usr/bin/env bash
# ============================================================================
# Story 19.11 — guard tests of scripts/load/measure-tenants.sh (no database touched)
# ============================================================================
# - syntax (bash -n), --help prints the usage and exits 0;
# - refused, exit != 0, before any docker / mvn / java / curl call (stubs record any call; --help too): load database = keevo_dev,
#   = the source, a "prod" name (load or source), postgres/template1, an invalid name; port 4500; a non-foundation module; an unknown
#   option.
# Usage: bash keevo/backend/scripts/tests/measure-tenants-guard-test.sh
# ============================================================================
set -euo pipefail

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="${SCRIPTS_DIR}/load/measure-tenants.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

failures=0
pass() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1" >&2; failures=$((failures + 1)); }

# a docker stub that records any call: the guards must refuse before touching a database
mkdir -p "${WORK}/bin"
cat > "${WORK}/bin/docker" <<STUB
#!/usr/bin/env bash
echo "docker \$*" >> "${WORK}/docker-calls"
exit 1
STUB
chmod +x "${WORK}/bin/docker"
# mvn / java / curl stubs too: neither --help nor a refused run may build, start the app or call it
for tool in mvn java curl; do
  printf '#!/usr/bin/env bash\necho "%s $*" >> "%s/docker-calls"\nexit 1\n' "${tool}" "${WORK}" > "${WORK}/bin/${tool}"
  chmod +x "${WORK}/bin/${tool}"
done
export PATH="${WORK}/bin:${PATH}"

if bash -n "${HARNESS}"; then pass "bash -n"; else fail "bash -n"; fi

if out="$(timeout 10 bash "${HARNESS}" --help)" && grep -q "^Usage: measure-tenants.sh" <<<"${out}" \
    && grep -q -- "--load-db" <<<"${out}"; then
  pass "--help prints the usage, exit 0"
else
  fail "--help"
fi

refused() { # refused <label> <expected stderr fragment> <args…>
  local label="$1" expected="$2"; shift 2
  local err status=0
  err="$(timeout 10 bash "${HARNESS}" "$@" 2>&1 >/dev/null)" || status=$?
  if [[ "${status}" -ne 0 ]] && grep -qi -- "${expected}" <<<"${err}"; then
    pass "${label} refused (exit ${status})"
  else
    fail "${label}: exit ${status}, stderr: ${err}"
  fi
}

refused "load db keevo_dev" "refused" --load-db keevo_dev
refused "load db = source" "refused" --source-db keevo_copy --load-db keevo_copy
refused "prod-like load db" "refused" --load-db keevo_prod_copy
refused "prod-like source db" "refused" --source-db keevo_prod --load-db keevo_load_x
refused "load db postgres" "refused" --load-db postgres
refused "load db template1" "refused" --load-db template1
refused "invalid load db name" "invalid" --load-db 'x; DROP DATABASE keevo_dev'
refused "port 4500" "refused" --port 4500
refused "non-foundation module" "foundation" --module invoicing
refused "unknown option" "unknown option" --frobnicate

if [[ -e "${WORK}/docker-calls" ]]; then
  fail "docker/mvn/java/curl called by --help or a refused run: $(cat "${WORK}/docker-calls")"
else
  pass "no docker/mvn/java/curl call by --help or a refused run"
fi

if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} failure(s)" >&2
  exit 1
fi
echo "all guard tests passed"
