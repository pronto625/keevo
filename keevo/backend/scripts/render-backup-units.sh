#!/usr/bin/env bash
# ============================================================================
# Keevo — render the backup systemd units (Story 10-7)
# ============================================================================
# Replaces the __KEEVO_HOME__ placeholder of keevo-backup.service and
# keevo-backup-alert@.service with the deploy directory, and copies the timer.
# Used by deploy-backend.yml and by tests/backup-alert-test.sh (single source).
#
# Usage: render-backup-units.sh <keevo_home> <source_dir> <output_dir>
#   keevo_home — deploy directory on the host, e.g. /home/<deploy-user>/keevo
# Exit 1 when a placeholder is left in a rendered unit.
# ============================================================================
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <keevo_home> <source_dir> <output_dir>" >&2
  exit 64
fi
KEEVO_HOME="$1"
SRC_DIR="$2"
OUT_DIR="$3"

# Escape the sed replacement: backslash, '&' and the '#' delimiter.
ESCAPED_HOME="$(printf '%s' "${KEEVO_HOME}" | sed -e 's/[\\&#]/\\&/g')"

mkdir -p "${OUT_DIR}"
for unit in keevo-backup.service keevo-backup-alert@.service; do
  sed "s#__KEEVO_HOME__#${ESCAPED_HOME}#g" "${SRC_DIR}/${unit}" > "${OUT_DIR}/${unit}"
  if grep -q "__KEEVO_HOME__" "${OUT_DIR}/${unit}"; then
    echo "❌ __KEEVO_HOME__ placeholder left in ${unit}" >&2
    exit 1
  fi
done
cp "${SRC_DIR}/keevo-backup.timer" "${OUT_DIR}/keevo-backup.timer"
