#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# measure-tenants.sh — Story 19.11 (FUS-NFR3, R1): load measurement of the backend with many tenant schemas.
#
# Runs against a THROWAWAY copy of a database (CREATE DATABASE <load> TEMPLATE <source>), never against the source:
#   1. clone        CREATE DATABASE <load> TEMPLATE <source> (the source must have no open connection)
#   2. boot         start the backend on <load> (port 4600 by default, messaging providers off, scheduling off,
#                   prod-like log levels): a first boot (catch-up of scripts newer than the source), then a warm boot;
#                   time to "Started KeevoApplication" and the "Tenant tables: …" summary of each
#   3. migration    delete the latest flyway_history_<module> row (default messaging) in every live schema, restart:
#                   the boot upgrade re-applies that script to every schema (duration, checked/provisioned/failed)
#   4. provisioning POST /api/v1/auth/register × N with throwaway phone numbers (+2376990xxxxx), min/median/max
#   5. backup       pg_dump -Fc of <load> inside the container, pg_restore into <load>_restore; both timed, sizes
#   6. summary      app stopped by port (fuser), Markdown table (metric, value, target, OK/KO), schema count
# Throwaway databases are dropped at the end (unless --keep), also on failure or Ctrl-C.
#
# Usage: scripts/load/measure-tenants.sh [options]        (run from anywhere; see --help)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

SOURCE_DB="${KEEVO_LOAD_SOURCE_DB:-keevo_dev}"
LOAD_DB="${KEEVO_LOAD_DB:-keevo_load}"
CONTAINER="${KEEVO_LOAD_CONTAINER:-keevo_postgres}"
PG_USER="${KEEVO_LOAD_PG_USER:-keevo}"
PG_PASSWORD="${KEEVO_LOAD_PG_PASSWORD:-${POSTGRES_PASSWORD:-keevo_local_pwd}}"
PG_HOST="${KEEVO_LOAD_PG_HOST:-localhost}"
PG_PORT="${KEEVO_LOAD_PG_PORT:-5444}"
APP_PORT="${KEEVO_LOAD_APP_PORT:-4600}"
REGISTRATIONS="${KEEVO_LOAD_REGISTRATIONS:-10}"
MODULE="${KEEVO_LOAD_MODULE:-messaging}"
JAR="${KEEVO_LOAD_JAR:-}"
BUILD=1
KEEP=0
MIN_MEMORY_MB="${KEEVO_LOAD_MIN_MEMORY_MB:-4096}"
MEMORY_WAIT_MAX_S="${KEEVO_LOAD_MEMORY_WAIT_MAX_S:-1800}"
BOOT_TIMEOUT_S="${KEEVO_LOAD_BOOT_TIMEOUT_S:-1200}"
JAVA_OPTS="${KEEVO_LOAD_JAVA_OPTS:--Xmx2g}"
OUT_FILE="${KEEVO_LOAD_OUT:-}"
DUMP_CHUNK="${KEEVO_LOAD_DUMP_CHUNK:-100}"
LOG_LEVELS=()
EXTRACT=0
PARALLELISM=""

FOUNDATION_MODULES="core store catalog inventory crm pos sync reporting messaging"

usage() {
  # quoted here-document: nothing in the help text is ever expanded or executed
  cat <<'EOF'
Usage: measure-tenants.sh [options]

Measures boot, one module migration on every schema, tenant provisioning and backup/restore on a THROWAWAY clone of
a database (Story 19.11). Never touches the source database; never uses port 4500; messaging providers and
scheduling are off in the measured app.

Options (environment variable in brackets):
  --source-db NAME     database cloned as template, must have no open connection  [KEEVO_LOAD_SOURCE_DB] (keevo_dev)
  --load-db NAME       throwaway database created from the template               [KEEVO_LOAD_DB] (keevo_load)
                       refused when equal to the source, keevo_dev, postgres, template*, or containing "prod"
  --container NAME     PostgreSQL docker container                                [KEEVO_LOAD_CONTAINER] (keevo_postgres)
  --pg-host HOST       host the app connects to                                    [KEEVO_LOAD_PG_HOST] (localhost)
  --pg-port PORT       port the app connects to                                    [KEEVO_LOAD_PG_PORT] (5444)
  --pg-user USER       database user (container superuser)                         [KEEVO_LOAD_PG_USER] (keevo)
                       password: [KEEVO_LOAD_PG_PASSWORD] or [POSTGRES_PASSWORD]
  --port PORT          app HTTP port, never 4500                                   [KEEVO_LOAD_APP_PORT] (4600)
  --registrations N    registrations timed                                         [KEEVO_LOAD_REGISTRATIONS] (10)
  --module ID          foundation module whose latest script is re-applied          [KEEVO_LOAD_MODULE] (messaging)
  --jar PATH           backend jar to run (implies --no-build)                     [KEEVO_LOAD_JAR]
  --no-build           do not run 'mvn -o -q -DskipTests package' first (uses target/keevo-backend-*.jar)
  --out FILE           also write the measures as key=value lines                  [KEEVO_LOAD_OUT]
  --keep               keep the load database at the end (the restore database is always dropped)
  --extract            run the jar extracted (java -Djarmode=tools extract: plain jars in lib/, no nested jar), the
                       layout of mvn spring-boot:run; default: the packaged jar, as the Docker image runs it
  --parallelism N      keevo.tenant-schema.upgrade-parallelism of the measured app (default: the app's default)
  --log-level L=LEVEL  extra app log level, repeatable (e.g. org.flywaydb=DEBUG); only logging levels are accepted
  --dump-chunk N       schemas per pg_dump when the single dump fails for lack of lock slots
                       (max_locks_per_transaction)                                 [KEEVO_LOAD_DUMP_CHUNK] (100)
  -h, --help           this help

Other environment: KEEVO_LOAD_MIN_MEMORY_MB (4096: available memory required before starting the app and before the
dump, waits up to KEEVO_LOAD_MEMORY_WAIT_MAX_S=1800 s), KEEVO_LOAD_BOOT_TIMEOUT_S (1200), KEEVO_LOAD_JAVA_OPTS (-Xmx2g).

Targets (FUS-NFR3): boot < 120 s, provisioning of one tenant < 5 s, one module migration on all schemas < 600 s,
backup/restore measured (no target). The app log of each boot stays in a temporary directory printed at the end.
Staging: same script with --container / --pg-* / --source-db pointing at the staging copy.
EOF
}

die() { echo "[load][ERROR] $*" >&2; exit 2; }
log() { echo "[load] $*" >&2; }

# ── arguments ────────────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --source-db) SOURCE_DB="${2:?}"; shift 2 ;;
    --load-db) LOAD_DB="${2:?}"; shift 2 ;;
    --container) CONTAINER="${2:?}"; shift 2 ;;
    --pg-host) PG_HOST="${2:?}"; shift 2 ;;
    --pg-port) PG_PORT="${2:?}"; shift 2 ;;
    --pg-user) PG_USER="${2:?}"; shift 2 ;;
    --port) APP_PORT="${2:?}"; shift 2 ;;
    --registrations) REGISTRATIONS="${2:?}"; shift 2 ;;
    --module) MODULE="${2:?}"; shift 2 ;;
    --jar) JAR="${2:?}"; BUILD=0; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --out) OUT_FILE="${2:?}"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --log-level) LOG_LEVELS+=("${2:?}"); shift 2 ;;
    --extract) EXTRACT=1; shift ;;
    --parallelism) PARALLELISM="${2:?}"; shift 2 ;;
    --dump-chunk) DUMP_CHUNK="${2:?}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown option: $1" ;;
  esac
done

RESTORE_DB="${LOAD_DB}_restore"

# ── guards (before anything touches a database) ──────────────────────────────
ident_ok() { [[ "$1" =~ ^[a-z_][a-z0-9_]{0,50}$ ]]; }
protected_db() {
  local name="$1"
  case "$name" in
    keevo_dev|keevo_postgres|keevo|postgres|template0|template1) return 0 ;;
  esac
  [[ "$name" == *prod* ]] && return 0
  [[ "$name" == "$SOURCE_DB" ]] && return 0
  return 1
}
ident_ok "$SOURCE_DB" || die "invalid source database name"
[[ "${SOURCE_DB,,}" == *prod* ]] && die "refused: the source database '${SOURCE_DB}' looks like a production database"
ident_ok "$LOAD_DB" || die "invalid load database name (lowercase letters, digits, _)"
ident_ok "$RESTORE_DB" || die "invalid restore database name"
protected_db "$LOAD_DB" && die "refused: the load database '${LOAD_DB}' is the source or a protected database"
protected_db "$RESTORE_DB" && die "refused: the restore database '${RESTORE_DB}' is the source or a protected database"
[[ "$APP_PORT" =~ ^[0-9]{2,5}$ ]] || die "invalid --port"
[[ "$APP_PORT" == "4500" ]] && die "refused: port 4500 is the local backend's port"
[[ "$REGISTRATIONS" =~ ^[0-9]{1,4}$ && "$REGISTRATIONS" -ge 1 && "$REGISTRATIONS" -le 9999 ]] \
  || die "--registrations must be 1..9999"
[[ " ${FOUNDATION_MODULES} " == *" ${MODULE} "* ]] \
  || die "--module must be a foundation module (${FOUNDATION_MODULES}): only their scripts are idempotent forever"
for level in "${LOG_LEVELS[@]+"${LOG_LEVELS[@]}"}"; do
  [[ "$level" =~ ^[A-Za-z0-9_.]+=(TRACE|DEBUG|INFO|WARN|ERROR|OFF)$ ]] || die "invalid --log-level '${level}'"
done
[[ -z "$PARALLELISM" || "$PARALLELISM" =~ ^[0-9]{1,2}$ && "$PARALLELISM" -ge 1 ]] || die "--parallelism must be 1..99"
[[ "$DUMP_CHUNK" =~ ^[0-9]{1,4}$ && "$DUMP_CHUNK" -ge 1 ]] || die "--dump-chunk must be a positive number"
command -v docker >/dev/null || die "docker not found"
command -v fuser >/dev/null || die "fuser not found (psmisc)"
command -v curl >/dev/null || die "curl not found"
command -v java >/dev/null || die "java not found"

# ── helpers ──────────────────────────────────────────────────────────────────
psql_c() { # psql_c <db> <sql> — one statement, unaligned, quiet
  docker exec -i "$CONTAINER" psql -U "$PG_USER" -d "$1" -v ON_ERROR_STOP=1 -Atq -c "$2"
}
now_ms() { date +%s%3N; }
available_mb() { free -m | awk '/^Mem:/{print $7}'; }
wait_for_memory() {
  local waited=0
  while [[ "$(available_mb)" -lt "$MIN_MEMORY_MB" ]]; do
    [[ "$waited" -ge "$MEMORY_WAIT_MAX_S" ]] && die "less than ${MIN_MEMORY_MB} MB available after ${waited} s"
    log "waiting for memory before $1: $(available_mb) MB available, ${MIN_MEMORY_MB} MB required"
    sleep 30; waited=$((waited + 30))
  done
}
port_busy() { fuser "${APP_PORT}/tcp" >/dev/null 2>&1; }

WORK_DIR="$(mktemp -d -t keevo-load-XXXXXX)"
APP_PID=""
declare -A M=()   # measures
record() { M["$1"]="$2"; }

stop_app() {
  if port_busy; then
    fuser -k -TERM "${APP_PORT}/tcp" >/dev/null 2>&1 || true
  fi
  if [[ -n "$APP_PID" ]]; then
    local i
    for i in $(seq 1 60); do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    if kill -0 "$APP_PID" 2>/dev/null; then
      if port_busy; then fuser -k "${APP_PORT}/tcp" >/dev/null 2>&1 || true; fi
      kill -9 "$APP_PID" 2>/dev/null || true # our own child, still booting (no port bound yet)
    fi
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
}

cleanup() {
  local status=$?
  stop_app
  docker exec "$CONTAINER" rm -rf "/tmp/${LOAD_DB}-dump" >/dev/null 2>&1 || true
  psql_c postgres "DROP DATABASE IF EXISTS ${RESTORE_DB} WITH (FORCE)" >/dev/null 2>&1 || true
  if [[ "$KEEP" -eq 0 ]]; then
    psql_c postgres "DROP DATABASE IF EXISTS ${LOAD_DB} WITH (FORCE)" >/dev/null 2>&1 || true
    log "throwaway databases dropped"
  else
    log "kept database ${LOAD_DB} (--keep); ${RESTORE_DB} dropped"
  fi
  log "app logs: ${WORK_DIR}"
  exit "$status"
}

# ── preflight ────────────────────────────────────────────────────────────────
docker exec "$CONTAINER" true 2>/dev/null || die "container ${CONTAINER} not running"
port_busy && die "port ${APP_PORT} already in use"
[[ "$(psql_c postgres "SELECT count(*) FROM pg_database WHERE datname = '${SOURCE_DB}'")" == "1" ]] \
  || die "source database ${SOURCE_DB} not found"
[[ "$(psql_c postgres "SELECT count(*) FROM pg_database WHERE datname IN ('${LOAD_DB}', '${RESTORE_DB}')")" == "0" ]] \
  || die "${LOAD_DB} or ${RESTORE_DB} already exists — drop it first (left by an earlier --keep run?)"
OPEN="$(psql_c postgres "SELECT count(*) FROM pg_stat_activity WHERE datname = '${SOURCE_DB}'")"
[[ "$OPEN" == "0" ]] || die "${OPEN} open connection(s) on ${SOURCE_DB} (a running backend?): stop them first — this \
script never kills them"

if [[ "$BUILD" -eq 1 ]]; then
  wait_for_memory "the build"
  log "building the backend jar (mvn -o -q -DskipTests package)"
  (cd "$BACKEND_DIR" && mvn -o -q -DskipTests package) >"${WORK_DIR}/build.log" 2>&1 \
    || die "build failed (see ${WORK_DIR}/build.log)"
fi
if [[ -z "$JAR" ]]; then
  for candidate in "${BACKEND_DIR}"/target/keevo-backend-*.jar; do
    [[ -f "$candidate" && ( -z "$JAR" || "$candidate" -nt "$JAR" ) ]] && JAR="$candidate"
  done
fi
[[ -f "$JAR" ]] || die "backend jar not found (build it, or pass --jar)"
APP_LAYOUT="packaged jar"
if [[ "$EXTRACT" -eq 1 ]]; then
  java -Djarmode=tools -jar "$JAR" extract --destination "${WORK_DIR}/app" >"${WORK_DIR}/extract.log" 2>&1 \
    || die "jar extraction failed (see ${WORK_DIR}/extract.log)"
  JAR="${WORK_DIR}/app/$(basename "$JAR")"
  [[ -f "$JAR" ]] || die "extracted jar not found"
  APP_LAYOUT="extracted jar (lib/)"
fi

trap cleanup EXIT
trap 'exit 130' INT TERM

# ── 1. clone ─────────────────────────────────────────────────────────────────
log "cloning ${SOURCE_DB} → ${LOAD_DB}"
t0=$(now_ms)
psql_c postgres "CREATE DATABASE ${LOAD_DB} TEMPLATE ${SOURCE_DB}" >/dev/null
record clone_ms $(( $(now_ms) - t0 ))
LIVE_SQL="SELECT count(*) FROM public.tenants t WHERE t.status <> 'DELETED' AND EXISTS (SELECT 1 FROM pg_namespace n WHERE n.nspname = t.schema_name)"
record live_tenants "$(psql_c "$LOAD_DB" "$LIVE_SQL")"
record kv_schemas "$(psql_c "$LOAD_DB" "SELECT count(*) FROM pg_namespace WHERE nspname ~ '^kv_[a-z0-9]{6}$'")"
record db_size_before "$(psql_c "$LOAD_DB" "SELECT pg_size_pretty(pg_database_size('${LOAD_DB}'))")"
record pg_version "$(psql_c "$LOAD_DB" "SHOW server_version")"
record max_connections "$(psql_c "$LOAD_DB" "SHOW max_connections")"
log "${M[live_tenants]} live tenant schemas (${M[kv_schemas]} kv_ schemas), ${M[db_size_before]}"

# ── app lifecycle ────────────────────────────────────────────────────────────
start_app() { # start_app <label>: boots and waits for "Started KeevoApplication"; records <label>_*
  local label="$1" logf="${WORK_DIR}/$1.log" t_start line level
  local extra=()
  for level in "${LOG_LEVELS[@]+"${LOG_LEVELS[@]}"}"; do extra+=("--logging.level.${level}"); done
  [[ -n "$PARALLELISM" ]] && extra+=("--keevo.tenant-schema.upgrade-parallelism=${PARALLELISM}")
  wait_for_memory "the ${label} boot"
  port_busy && die "port ${APP_PORT} already in use"
  log "starting the app (${label}) on port ${APP_PORT}"
  t_start=$(now_ms)
  # run from an empty directory: application.yml imports ./.env, which must not leak real provider keys in
  # shellcheck disable=SC2086
  # the password goes through the environment, never on the command line (visible in ps)
  (cd "$WORK_DIR" && SPRING_DATASOURCE_PASSWORD="${PG_PASSWORD}" exec java ${JAVA_OPTS} -jar "$JAR" \
      --spring.profiles.active=load \
      --server.port="${APP_PORT}" \
      --spring.datasource.url="jdbc:postgresql://${PG_HOST}:${PG_PORT}/${LOAD_DB}" \
      --spring.datasource.username="${PG_USER}" \
      --keevo.whatsapp.provider=noop \
      --keevo.fcm.enabled=false \
      --keevo.push.expo.enabled=false \
      --keevo.sms.providers= \
      --keevo.email.providers= \
      --keevo.scheduling.enabled=false \
      --logging.level.root=WARN \
      --logging.level.com.keevo=INFO \
      --logging.level.org.flywaydb=WARN \
      --logging.file.name= \
      "${extra[@]+"${extra[@]}"}" ) >"$logf" 2>&1 &
  APP_PID=$!
  while :; do
    if grep -q "Started KeevoApplication" "$logf"; then break; fi
    if ! kill -0 "$APP_PID" 2>/dev/null; then APP_PID=""; die "app (${label}) exited during boot — see ${logf}"; fi
    if (( ($(now_ms) - t_start) / 1000 > BOOT_TIMEOUT_S )); then die "app (${label}) not started after ${BOOT_TIMEOUT_S} s"; fi
    sleep 1
  done
  record "${label}_wall_ms" $(( $(now_ms) - t_start ))
  line="$(grep -o 'Started KeevoApplication in [0-9.]* seconds' "$logf" | head -1)"
  record "${label}_started_s" "$(sed -E 's/.* in ([0-9.]+) seconds/\1/' <<<"$line")"
  line="$(grep -oE 'Tenant tables: [0-9]+ tenant\(s\) checked, [0-9]+ provisioned, [0-9]+ (failed|FAILED)[^0-9]*[0-9]+ ms' "$logf" | head -1 || true)"
  if [[ -n "$line" ]]; then
    record "${label}_checked" "$(sed -E 's/.*: ([0-9]+) tenant.*/\1/' <<<"$line")"
    record "${label}_provisioned" "$(sed -E 's/.* ([0-9]+) provisioned.*/\1/' <<<"$line")"
    record "${label}_failed" "$(sed -E 's/.* ([0-9]+) (failed|FAILED).*/\1/' <<<"$line")"
    record "${label}_upgrade_ms" "$(sed -E 's/.* ([0-9]+) ms$/\1/' <<<"$line")"
  else
    record "${label}_checked" "?"; record "${label}_provisioned" "?"; record "${label}_failed" "?"
    record "${label}_upgrade_ms" "?"
  fi
  log "${label}: started in ${M[${label}_started_s]} s — tenant tables ${M[${label}_checked]} checked, \
${M[${label}_provisioned]} provisioned, ${M[${label}_failed]} failed, ${M[${label}_upgrade_ms]} ms"
}

# ── 2. boot: catch-up (scripts newer than the source), then warm ─────────────
start_app boot_first
stop_app
start_app boot_warm
stop_app

# ── 3. one module migration on every live schema ─────────────────────────────
HISTORY="flyway_history_${MODULE//-/_}"
log "deleting the latest ${HISTORY} row in every live schema"
record migration_rows_deleted "$(docker exec -i "$CONTAINER" psql -U "$PG_USER" -d "$LOAD_DB" -v ON_ERROR_STOP=1 -Atq <<SQL
CREATE TEMP TABLE _deleted (n int);
DO \$\$
DECLARE
    s text;
    n int := 0;
    k int;
BEGIN
    FOR s IN
        SELECT t.schema_name FROM public.tenants t
        WHERE t.status <> 'DELETED' AND t.schema_name ~ '^kv_[a-z0-9]{6}$'
          AND to_regclass(format('%I.%I', t.schema_name, '${HISTORY}')) IS NOT NULL
    LOOP
        EXECUTE format('DELETE FROM %I.%I WHERE installed_rank = (SELECT max(installed_rank) FROM %I.%I)',
                       s, '${HISTORY}', s, '${HISTORY}');
        GET DIAGNOSTICS k = ROW_COUNT;
        n := n + k;
    END LOOP;
    INSERT INTO _deleted VALUES (n);
END
\$\$;
SELECT n FROM _deleted;
SQL
)"
start_app migration

# ── 4. provisioning: registrations through the API ───────────────────────────
log "timing ${REGISTRATIONS} registration(s)"
EXISTING="$(psql_c "$LOAD_DB" "SELECT count(*) FROM public.users WHERE phone_number LIKE '+2376990%'")"
[[ "$EXISTING" == "0" ]] || log "note: ${EXISTING} phone number(s) of the +2376990 range already present — skipped"
TIMES_FILE="${WORK_DIR}/registrations.txt"
: >"$TIMES_FILE"
seq_no=0
done_count=0
errors=0
while [[ "$done_count" -lt "$REGISTRATIONS" ]]; do
  seq_no=$((seq_no + 1))
  [[ "$seq_no" -le 99999 ]] || die "no free throwaway phone number left"
  phone="$(printf '+2376990%05d' "$seq_no")"
  taken="$(psql_c "$LOAD_DB" "SELECT count(*) FROM public.users WHERE phone_number = '${phone}'")"
  [[ "$taken" == "0" ]] || continue
  result="$(curl -sS -o "${WORK_DIR}/register.json" -w '%{http_code} %{time_total}' \
      -H 'Content-Type: application/json' \
      -d "{\"phoneNumber\":\"${phone}\",\"password\":\"LoadTest#2026x\"}" \
      "http://localhost:${APP_PORT}/api/v1/auth/register" || echo "000 0")"
  code="${result%% *}"
  if [[ "$code" != "201" ]]; then
    errors=$((errors + 1))
    log "registration answered HTTP ${code}"
    if [[ "$errors" -ge 3 ]]; then
      log "registrations failing (HTTP ${code}) — see ${WORK_DIR}/register.json; provisioning not measured"
      break
    fi
    continue
  fi
  echo "${result#* }" >>"$TIMES_FILE"
  done_count=$((done_count + 1))
done
record registrations "$done_count"
record register_errors "$errors"
[[ -s "$TIMES_FILE" ]] || echo "?" >"$TIMES_FILE"
record register_min_s "$(sort -n "$TIMES_FILE" | head -1)"
record register_max_s "$(sort -n "$TIMES_FILE" | tail -1)"
record register_median_s "$(sort -n "$TIMES_FILE" | awk '{v[NR]=$1} END{ if (NR%2) print v[(NR+1)/2]; else printf "%.6f\n", (v[NR/2]+v[NR/2+1])/2 }')"
record live_tenants_after "$(psql_c "$LOAD_DB" "$LIVE_SQL")"
stop_app

# ── 5. backup / restore inside the container ─────────────────────────────────
# One pg_dump locks every table of the database in one transaction: with thousands of tenant tables it can exceed the
# server's lock table (max_locks_per_transaction × max_connections) — recorded, then measured again in chunks of
# --dump-chunk schemas (one pg_dump per chunk: the non-tenant part first, then the kv_ schemas), restored in order.
wait_for_memory "the dump"
record relations "$(psql_c "$LOAD_DB" "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r','p','S','v','m','f') AND n.nspname NOT IN ('pg_catalog','information_schema') AND n.nspname NOT LIKE 'pg_toast%'")"
record max_locks_per_transaction "$(psql_c "$LOAD_DB" "SHOW max_locks_per_transaction")"
DUMP_DIR="/tmp/${LOAD_DB}-dump"
docker exec "$CONTAINER" sh -c "rm -rf '${DUMP_DIR}' && mkdir -p '${DUMP_DIR}'"
log "pg_dump -Fc ${LOAD_DB} (single dump)"
t0=$(now_ms)
if docker exec "$CONTAINER" sh -c "pg_dump -U '${PG_USER}' -Fc --no-owner --no-acl -f '${DUMP_DIR}/000-all.dump' '${LOAD_DB}'" \
    >"${WORK_DIR}/dump.log" 2>&1; then
  record dump_mode "single"
else
  record dump_single "KO after $(( $(now_ms) - t0 )) ms: $(grep -o 'ERROR: *[^:]*$\|out of shared memory' "${WORK_DIR}/dump.log" | head -1)"
  log "single dump failed (${M[dump_single]}) — dumping in chunks of ${DUMP_CHUNK} schemas"
  docker exec "$CONTAINER" sh -c "rm -f '${DUMP_DIR}'/*"
  record dump_mode "chunks of ${DUMP_CHUNK} schemas"
  t0=$(now_ms)
  DUMP_FAILED_CHUNKS=""
  docker exec "$CONTAINER" sh -c "pg_dump -U '${PG_USER}' -Fc --no-owner --no-acl -N 'kv_*' -f '${DUMP_DIR}/000-base.dump' '${LOAD_DB}'" \
    >>"${WORK_DIR}/dump.log" 2>&1 || DUMP_FAILED_CHUNKS+="000 "
  mapfile -t KV_SCHEMAS < <(psql_c "$LOAD_DB" "SELECT nspname FROM pg_namespace WHERE nspname ~ '^kv_' ORDER BY 1")
  chunk=0
  for ((i = 0; i < ${#KV_SCHEMAS[@]}; i += DUMP_CHUNK)); do
    chunk=$((chunk + 1))
    args=""
    for s in "${KV_SCHEMAS[@]:i:DUMP_CHUNK}"; do
      ident_ok "$s" || die "unexpected schema name"
      args+=" -n ${s}"
    done
    docker exec "$CONTAINER" sh -c "pg_dump -U '${PG_USER}' -Fc --no-owner --no-acl ${args} -f '${DUMP_DIR}/$(printf '%03d' "$chunk")-kv.dump' '${LOAD_DB}'" \
      >>"${WORK_DIR}/dump.log" 2>&1 || DUMP_FAILED_CHUNKS+="$(printf '%03d' "$chunk") "
  done
  record dump_failed_chunks "${DUMP_FAILED_CHUNKS:-none}"
  record dump_chunks "$((chunk + 1))"
fi
record dump_ms $(( $(now_ms) - t0 ))
record dump_bytes "$(docker exec "$CONTAINER" sh -c "cat '${DUMP_DIR}'/*.dump | wc -c")"
record db_size_after "$(psql_c "$LOAD_DB" "SELECT pg_size_pretty(pg_database_size('${LOAD_DB}'))")"
log "pg_restore into ${RESTORE_DB}"
psql_c postgres "CREATE DATABASE ${RESTORE_DB} OWNER ${PG_USER}" >/dev/null
t0=$(now_ms)
docker exec "$CONTAINER" sh -c "for f in \$(ls '${DUMP_DIR}'/*.dump | sort); do pg_restore -U '${PG_USER}' -d '${RESTORE_DB}' --no-owner --no-acl \"\$f\" || echo \"pg_restore: error: exit \$? on \$f\"; done" \
  >"${WORK_DIR}/restore.log" 2>&1 || true
record restore_ms $(( $(now_ms) - t0 ))
record restore_errors "$(grep -c '^pg_restore: error' "${WORK_DIR}/restore.log" || true)"
record restore_failed_chunks "$(grep -o 'exit [0-9]* on .*/[0-9]*-[a-z]*\.dump' "${WORK_DIR}/restore.log" | sed -E 's#.*/([0-9]+-[a-z]+)\.dump#\1#' | paste -sd' ' -)"
[[ -n "${M[restore_failed_chunks]}" ]] || record restore_failed_chunks "none"
record restore_tenants "$(psql_c "$RESTORE_DB" "$LIVE_SQL")"
psql_c postgres "DROP DATABASE IF EXISTS ${RESTORE_DB} WITH (FORCE)" >/dev/null
docker exec "$CONTAINER" rm -rf "${DUMP_DIR}"

# ── 6. summary ───────────────────────────────────────────────────────────────
verdict() { # verdict <value> <limit> : OK when value < limit
  awk -v v="$1" -v l="$2" 'BEGIN{ if (v == "?" || v == "") print "?"; else if (v + 0 < l + 0) print "OK"; else print "KO" }'
}
verdict_failed() { # verdict_failed <value> <limit> <failed tenants> : KO as soon as one tenant failed
  if [[ "$3" != "0" ]]; then echo "KO"; else verdict "$1" "$2"; fi
}
secs() { awk -v ms="$1" 'BEGIN{ if (ms == "?") print "?"; else printf "%.1f", ms / 1000 }'; }

{
  echo
  echo "## Load measurement — $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "Clone of \`${SOURCE_DB}\`: ${M[live_tenants]} live tenant schemas (${M[kv_schemas]} kv_ schemas), \
${M[db_size_before]}; PostgreSQL ${M[pg_version]}; app port ${APP_PORT} (${APP_LAYOUT}, upgrade parallelism ${PARALLELISM:-default}), profile load, providers off, scheduling off."
  echo
  echo "| Metric | Value | Target | Verdict |"
  echo "|---|---|---|---|"
  echo "| Boot, first on the clone (catch-up: ${M[boot_first_provisioned]} provisioned, ${M[boot_first_failed]} failed) | ${M[boot_first_started_s]} s (tenant tables $(secs "${M[boot_first_upgrade_ms]}") s) | < 120 s, 0 failed | $(verdict_failed "${M[boot_first_started_s]}" 120 "${M[boot_first_failed]}") |"
  echo "| Boot, warm (${M[boot_warm_checked]} checked, ${M[boot_warm_provisioned]} provisioned, ${M[boot_warm_failed]} failed) | ${M[boot_warm_started_s]} s (tenant tables $(secs "${M[boot_warm_upgrade_ms]}") s) | < 120 s, 0 failed | $(verdict_failed "${M[boot_warm_started_s]}" 120 "${M[boot_warm_failed]}") |"
  echo "| Migration \`${MODULE}\` (latest script) on all schemas: ${M[migration_provisioned]} provisioned, ${M[migration_failed]} failed | $(secs "${M[migration_upgrade_ms]}") s (boot ${M[migration_started_s]} s) | < 600 s, 0 failed | $(verdict_failed "$(secs "${M[migration_upgrade_ms]}")" 600 "${M[migration_failed]}") |"
  echo "| Provisioning, ${M[registrations]} registrations (min / median / max; ${M[register_errors]} HTTP error(s)) | ${M[register_min_s]} / ${M[register_median_s]} / ${M[register_max_s]} s | < 5 s | $(verdict "${M[register_max_s]}" 5) |"
  [[ -n "${M[dump_single]:-}" ]] && echo "| Backup, single \`pg_dump\` of the whole database (${M[relations]} relations, max_locks_per_transaction ${M[max_locks_per_transaction]}) | ${M[dump_single]} | succeeds | KO |"
  echo "| Backup \`pg_dump -Fc\`, ${M[dump_mode]} ($(awk -v b="${M[dump_bytes]}" 'BEGIN{printf "%.1f MB", b/1048576}'), database ${M[db_size_after]}; failed chunks: ${M[dump_failed_chunks]:-none}) | $(secs "${M[dump_ms]}") s | measured, no failed chunk | $([[ "${M[dump_failed_chunks]:-none}" == "none" ]] && echo "—" || echo "KO") |"
  echo "| Restore \`pg_restore\` (${M[restore_tenants]} live tenants restored, ${M[restore_errors]} error line(s), failed chunks: ${M[restore_failed_chunks]}) | $(secs "${M[restore_ms]}") s | measured, 0 error | $([[ "${M[restore_errors]}" == "0" ]] && echo "—" || echo "KO") |"
  echo
  echo "Schemas: ${M[live_tenants]} live before, ${M[live_tenants_after]} after the registrations; history rows deleted for the migration: ${M[migration_rows_deleted]}."
} | tee "${WORK_DIR}/summary.md"

if [[ -n "$OUT_FILE" ]]; then
  for k in "${!M[@]}"; do echo "${k}=${M[$k]}"; done | sort >"$OUT_FILE"
  log "measures written to ${OUT_FILE}"
fi
