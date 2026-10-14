#!/usr/bin/env bash
# ============================================================================
# Keevo — PostgreSQL + product photos Backup Script
# ============================================================================
# Performs a full pg_dump (all schemas: public + kv_*) of the Keevo PostgreSQL
# database, validates the dump, optionally uploads to S3, and rotates local
# files older than 30 days.
#
# Usage:
#   backup-db.sh
#
# Environment variables (required):
#   POSTGRES_HOST     — PostgreSQL host (ignored if BACKUP_MODE=docker-exec)
#   POSTGRES_PORT     — PostgreSQL port (default: 5432)
#   POSTGRES_USER     — PostgreSQL user
#   POSTGRES_PASSWORD — PostgreSQL password
#   POSTGRES_DB       — PostgreSQL database name
#
# Environment variables (optional):
#   BACKUP_MODE       — "docker-exec" (default) or "direct"
#   DOCKER_CONTAINER  — container name when BACKUP_MODE=docker-exec (default: keevo_postgres)
#   BACKUP_S3_BUCKET  — S3 bucket name (if unset, local-only backup)
#   BACKUP_S3_PREFIX  — S3 key prefix (default: keevo/)
#   BACKUP_LOCAL_DIR  — local backup directory (default: /var/backups/keevo)
#   BACKUP_REQUIRE_S3 — "1" in prod: a missing BACKUP_S3_BUCKET is a failure (exit 1)
#                       after the local backup is written
#   BACKUP_HEARTBEAT_URL — pinged (GET) at the end of a successful run only
#                       (healthchecks-style dead-man switch: the monitor alerts when
#                       no ping arrives within 26 h — catches a disabled timer or a dead host)
#   BACKUP_LOCK_STALE_SECONDS — lock age after which a held lock is reported as stale
#                       (default: 21600 = 6 h)
#   BACKUP_PHOTOS     — "1" (default) also archives the product photos (only copy, in the
#                       backend's Docker volume); "0" skips them
#   PHOTOS_CONTAINER  — container holding the photos volume (default: keevo_backend)
#   PHOTOS_DIR        — photos directory inside that container (default: /app/data/product-photos)
#
# Exit status (Story 10-7): any failure exits non-zero (strict mode + ERR trap), so that
# systemd's OnFailure= in keevo-backup.service starts the alert unit. The only exit 0
# without a backup is "another backup is already running" (lock held for less than 6 h);
# a lock held longer is reported as stale (exit 1 → alert).
# ============================================================================
set -euo pipefail
trap 'echo "[BACKUP][ERROR] Unexpected failure at line ${LINENO} (exit $?): ${BASH_COMMAND}" >&2' ERR

# ─── Configuration ───────────────────────────────────────────────────────────
TIMESTAMP=$(date -u +"%Y-%m-%dT%H-%M-%SZ")
FILENAME="keevo_backup_${TIMESTAMP}.dump"
LOCAL_DIR="${BACKUP_LOCAL_DIR:-/var/backups/keevo}"
LOCAL_FILE="${LOCAL_DIR}/${FILENAME}"
MODE="${BACKUP_MODE:-docker-exec}"
CONTAINER="${DOCKER_CONTAINER:-keevo_postgres}"
RETENTION_DAYS=30
PHOTOS_ENABLED="${BACKUP_PHOTOS:-1}"
PHOTOS_CONTAINER="${PHOTOS_CONTAINER:-keevo_backend}"
PHOTOS_DIR="${PHOTOS_DIR:-/app/data/product-photos}"
PHOTOS_FILENAME="keevo_photos_${TIMESTAMP}.tar.gz"
PHOTOS_FILE="${LOCAL_DIR}/${PHOTOS_FILENAME}"

# ─── Single-instance lock ─────────────────────────────────────────────────────
# Prevent concurrent runs (timer + manual, or Persistent=true catch-up) from
# launching two pg_dumps at once.
# The lock file mtime records when the holder started (opened in append mode so that
# a waiting run does not reset it); a lock older than the stale threshold means a stuck run.
LOCK_FILE="${BACKUP_LOCK_FILE:-/tmp/keevo-backup.lock}"
LOCK_STALE_SECONDS="${BACKUP_LOCK_STALE_SECONDS:-21600}"
exec 9>>"${LOCK_FILE}"
if ! flock -n 9; then
  lock_age=$(( $(date +%s) - $(stat -c %Y "${LOCK_FILE}") ))
  if (( lock_age > LOCK_STALE_SECONDS )); then
    echo "[BACKUP][ERROR] stale lock: ${LOCK_FILE} held for ${lock_age}s (> ${LOCK_STALE_SECONDS}s) — a previous backup is stuck, investigate" >&2
    exit 1
  fi
  echo "[BACKUP][WARN] Another backup is already running (lock: ${LOCK_FILE}, ${lock_age}s) — skipping this run" >&2
  exit 0
fi
touch "${LOCK_FILE}"

# ─── Validation ──────────────────────────────────────────────────────────────
for var in POSTGRES_USER POSTGRES_DB; do
  if [[ -z "${!var:-}" ]]; then
    echo "[BACKUP][ERROR] Required environment variable ${var} is not set (is .env.backup present next to the scripts directory?)" >&2
    exit 1
  fi
done

if [[ "${MODE}" == "direct" && -z "${POSTGRES_PASSWORD:-}" ]]; then
  echo "[BACKUP][ERROR] POSTGRES_PASSWORD is required when BACKUP_MODE=direct" >&2
  exit 1
fi

if [[ "${MODE}" == "docker-exec" ]] && ! docker info > /dev/null 2>&1; then
  echo "[BACKUP][ERROR] Docker daemon is not reachable (docker info failed) — cannot dump ${CONTAINER}" >&2
  exit 1
fi

mkdir -p "${LOCAL_DIR}"
echo "[BACKUP][INFO] Starting backup at ${TIMESTAMP} (mode: ${MODE})..."

# ─── Dump ────────────────────────────────────────────────────────────────────
dump_exit_code=0

if [[ "${MODE}" == "docker-exec" ]]; then
  # Dump via docker exec — no need for pg_dump on the host or exposed ports.
  # Capture stderr so the real failure cause is available (instead of being discarded).
  docker exec "${CONTAINER}" pg_dump \
    -U "${POSTGRES_USER}" \
    -Fc --no-owner --no-acl \
    "${POSTGRES_DB}" > "${LOCAL_FILE}" 2>"${LOCAL_FILE}.err" \
    || dump_exit_code=$?
else
  # Dump via direct connection — requires pg_dump installed on the host
  export PGPASSWORD="${POSTGRES_PASSWORD}"
  pg_dump \
    -h "${POSTGRES_HOST:-localhost}" \
    -p "${POSTGRES_PORT:-5432}" \
    -U "${POSTGRES_USER}" \
    -Fc --no-owner --no-acl \
    "${POSTGRES_DB}" > "${LOCAL_FILE}" \
    || dump_exit_code=$?
  unset PGPASSWORD
fi

if [[ ${dump_exit_code} -ne 0 ]]; then
  echo "[BACKUP][ERROR] pg_dump failed with exit code ${dump_exit_code}" >&2
  if [[ -s "${LOCAL_FILE}.err" ]]; then
    sed 's/^/[BACKUP][ERROR] /' "${LOCAL_FILE}.err" >&2
  fi
  rm -f "${LOCAL_FILE}" "${LOCAL_FILE}.err"
  exit 1
fi
rm -f "${LOCAL_FILE}.err"

# ─── Validate dump ──────────────────────────────────────────────────────────
# pg_restore --list validates the dump is non-empty and parseable.
# Falls back to file-size check when pg_restore is not installed on the host.
if command -v pg_restore &>/dev/null; then
  pg_restore --list "${LOCAL_FILE}" > /dev/null 2>&1 \
    || { echo "[BACKUP][ERROR] Dump validation failed: ${LOCAL_FILE}" >&2; exit 1; }
else
  # pg_restore not available — verify file exists and is non-empty
  if [[ ! -s "${LOCAL_FILE}" ]]; then
    echo "[BACKUP][ERROR] Dump file is empty or missing: ${LOCAL_FILE}" >&2
    exit 1
  fi
  echo "[BACKUP][WARN] pg_restore not found — skipped format validation (file size check only)"
fi

DUMP_SIZE=$(du -sh "${LOCAL_FILE}" 2>/dev/null | cut -f1 || true)
echo "[BACKUP][INFO] Dump validated: ${DUMP_SIZE:-unknown}"

# ─── Product photos archive ──────────────────────────────────────────────────
# The photos live only in the backend's Docker volume: archive them next to the dump.
# Streamed out of the running container (tar from the image's busybox), so no host path
# or volume name has to be known. A failure here never discards the DB dump: it is
# reported at the end (exit 1 → alert), like an S3 failure.
photos_failed=0
photos_archived=0
if [[ "${PHOTOS_ENABLED}" == "1" ]]; then
  if docker exec "${PHOTOS_CONTAINER}" test -d "${PHOTOS_DIR}" > /dev/null 2>&1; then
    if docker exec "${PHOTOS_CONTAINER}" tar -czf - -C "${PHOTOS_DIR}" . > "${PHOTOS_FILE}" 2>"${PHOTOS_FILE}.err" \
        && tar -tzf "${PHOTOS_FILE}" > /dev/null 2>&1; then
      rm -f "${PHOTOS_FILE}.err"
      photos_archived=1
      PHOTOS_SIZE=$(du -sh "${PHOTOS_FILE}" 2>/dev/null | cut -f1 || true)
      echo "[BACKUP][INFO] Photos archived: ${PHOTOS_FILENAME} (${PHOTOS_SIZE:-unknown})"
    else
      echo "[BACKUP][ERROR] Photos archive failed (${PHOTOS_CONTAINER}:${PHOTOS_DIR})" >&2
      if [[ -s "${PHOTOS_FILE}.err" ]]; then
        sed 's/^/[BACKUP][ERROR] /' "${PHOTOS_FILE}.err" >&2
      fi
      rm -f "${PHOTOS_FILE}" "${PHOTOS_FILE}.err"
      photos_failed=1
    fi
  else
    echo "[BACKUP][ERROR] Photos directory not reachable: ${PHOTOS_CONTAINER}:${PHOTOS_DIR} (backend down or volume missing)" >&2
    photos_failed=1
  fi
else
  echo "[BACKUP][INFO] Product photos backup disabled (BACKUP_PHOTOS=0)"
fi

# ─── Upload to S3 (optional) ────────────────────────────────────────────────
s3_failed=0
if [[ -n "${BACKUP_S3_BUCKET:-}" ]]; then
  S3_PREFIX="${BACKUP_S3_PREFIX:-keevo/}"
  S3_PATH="s3://${BACKUP_S3_BUCKET}/${S3_PREFIX}${FILENAME}"

  echo "[BACKUP][INFO] Uploading to ${S3_PATH}..."
  if aws s3 cp "${LOCAL_FILE}" "${S3_PATH}" --storage-class STANDARD_IA; then
    echo "[BACKUP][INFO] Uploaded to S3: ${S3_PATH}"
  else
    echo "[BACKUP][ERROR] S3 upload failed for ${S3_PATH}" >&2
    s3_failed=1
  fi
  if [[ ${photos_archived} -eq 1 ]]; then
    S3_PHOTOS_PATH="s3://${BACKUP_S3_BUCKET}/${S3_PREFIX}${PHOTOS_FILENAME}"
    if aws s3 cp "${PHOTOS_FILE}" "${S3_PHOTOS_PATH}" --storage-class STANDARD_IA; then
      echo "[BACKUP][INFO] Uploaded to S3: ${S3_PHOTOS_PATH}"
    else
      echo "[BACKUP][ERROR] S3 upload failed for ${S3_PHOTOS_PATH}" >&2
      s3_failed=1
    fi
  fi
elif [[ "${BACKUP_REQUIRE_S3:-0}" == "1" ]]; then
  echo "[BACKUP][ERROR] BACKUP_S3_BUCKET not set but BACKUP_REQUIRE_S3=1 — local backup only, no off-site copy" >&2
  s3_failed=1
else
  echo "[BACKUP][WARN] BACKUP_S3_BUCKET not set — local backup only (not production-safe)"
fi

# ─── Rotate local files older than 30 days ──────────────────────────────────
# Runs regardless of S3 outcome — otherwise a persistent S3 failure would skip
# rotation and let local backups fill the disk indefinitely.
ROTATED_COUNT=$(find "${LOCAL_DIR}" -type f \( -name "keevo_backup_*.dump" -o -name "keevo_photos_*.tar.gz" \) -mtime +${RETENTION_DAYS} -print -delete 2>/dev/null | wc -l || true)
echo "[BACKUP][INFO] Rotation done: ${ROTATED_COUNT} file(s) older than ${RETENTION_DAYS} days removed"

# Signal failure only after the local copy is safe and rotation has run.
if [[ ${photos_failed} -ne 0 ]]; then
  echo "[BACKUP][ERROR] Database backed up but product photos NOT archived — investigate" >&2
  exit 1
fi
if [[ ${s3_failed} -ne 0 ]]; then
  echo "[BACKUP][ERROR] Local backup kept but no off-site copy (S3 upload failed or bucket missing) — durability at risk, investigate" >&2
  exit 1
fi

echo "[BACKUP][INFO] Backup complete: ${FILENAME}"

# ─── Success heartbeat (optional) ────────────────────────────────────────────
# Only reached on success. A failed ping does not fail the backup: the monitor will
# alert on the missing ping anyway.
if [[ -n "${BACKUP_HEARTBEAT_URL:-}" ]]; then
  if curl -fsS --max-time 10 --retry 3 -o /dev/null "${BACKUP_HEARTBEAT_URL}"; then
    echo "[BACKUP][INFO] Heartbeat sent"
  else
    echo "[BACKUP][WARN] Heartbeat ping failed — the monitor will report a missed backup" >&2
  fi
fi
