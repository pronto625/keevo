#!/usr/bin/env bash
# ============================================================================
# Keevo — PostgreSQL Backup Script
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
# ============================================================================
set -euo pipefail

# ─── Configuration ───────────────────────────────────────────────────────────
TIMESTAMP=$(date -u +"%Y-%m-%dT%H-%M-%SZ")
FILENAME="keevo_backup_${TIMESTAMP}.dump"
LOCAL_DIR="${BACKUP_LOCAL_DIR:-/var/backups/keevo}"
LOCAL_FILE="${LOCAL_DIR}/${FILENAME}"
MODE="${BACKUP_MODE:-docker-exec}"
CONTAINER="${DOCKER_CONTAINER:-keevo_postgres}"
RETENTION_DAYS=30

# ─── Single-instance lock ─────────────────────────────────────────────────────
# Prevent concurrent runs (timer + manual, or Persistent=true catch-up) from
# launching two pg_dumps at once.
LOCK_FILE="${BACKUP_LOCK_FILE:-/tmp/keevo-backup.lock}"
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
  echo "[BACKUP][WARN] Another backup is already running (lock: ${LOCK_FILE}) — skipping this run" >&2
  exit 0
fi

# ─── Validation ──────────────────────────────────────────────────────────────
for var in POSTGRES_USER POSTGRES_DB; do
  if [[ -z "${!var:-}" ]]; then
    echo "[BACKUP][ERROR] Required environment variable ${var} is not set" >&2
    exit 1
  fi
done

if [[ "${MODE}" == "direct" && -z "${POSTGRES_PASSWORD:-}" ]]; then
  echo "[BACKUP][ERROR] POSTGRES_PASSWORD is required when BACKUP_MODE=direct" >&2
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
else
  echo "[BACKUP][WARN] BACKUP_S3_BUCKET not set — local backup only (not production-safe)"
fi

# ─── Rotate local files older than 30 days ──────────────────────────────────
# Runs regardless of S3 outcome — otherwise a persistent S3 failure would skip
# rotation and let local backups fill the disk indefinitely.
ROTATED_COUNT=$(find "${LOCAL_DIR}" -type f -name "keevo_backup_*.dump" -mtime +${RETENTION_DAYS} -print -delete 2>/dev/null | wc -l || true)
echo "[BACKUP][INFO] Rotation done: ${ROTATED_COUNT} file(s) older than ${RETENTION_DAYS} days removed"

# Signal failure only after the local copy is safe and rotation has run.
if [[ ${s3_failed} -ne 0 ]]; then
  echo "[BACKUP][ERROR] Local backup kept but S3 upload failed — durability at risk, investigate" >&2
  exit 1
fi

echo "[BACKUP][INFO] Backup complete: ${FILENAME}"
