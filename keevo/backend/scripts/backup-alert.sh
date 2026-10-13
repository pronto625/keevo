#!/usr/bin/env bash
# ============================================================================
# Keevo — backup failure alert (Story 10-7)
# ============================================================================
# Run by keevo-backup-alert@.service when keevo-backup.service fails (OnFailure=).
# Always logs the alert to journald (stderr, SyslogIdentifier=keevo-backup-alert),
# then POSTs a JSON message to BACKUP_ALERT_WEBHOOK_URL when it is set.
#
# Payload: {"text": "...", "content": "..."} — "text" is read by Slack-compatible
# webhooks and WhatsApp gateways, "content" by Discord-compatible ones. When
# BACKUP_ALERT_TO is set, a "to" field is added (e.g. WasenderAPI send-message).
#
# Usage: backup-alert.sh <failed-unit-name>
#
# Environment (optional):
#   BACKUP_ALERT_WEBHOOK_URL    — webhook endpoint
#   BACKUP_ALERT_WEBHOOK_TOKEN  — sent as "Authorization: Bearer <token>"
#   BACKUP_ALERT_TO             — recipient for WhatsApp gateways (E.164)
#   BACKUP_ALERT_FORCE_BUILTIN_JSON — tests only: build the JSON without jq
#
# The JSON is built with jq when available, else with the built-in escaper. The webhook
# URL, token and payload are passed to curl through stdin/a private temp file, never argv.
#
# Exit codes: 0 alert posted, 1 webhook call failed, 2 no webhook configured
# (the alert unit then shows as failed in `systemctl --failed`, on purpose).
# ============================================================================
set -euo pipefail

FAILED_UNIT="${1:-keevo-backup.service}"
HOST="$(hostname -f 2>/dev/null || hostname)"
NOW="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
RESULT="$(systemctl show -p Result --value "${FAILED_UNIT}" 2>/dev/null || echo "unknown")"

MESSAGE="Keevo : échec de la sauvegarde PostgreSQL sur ${HOST} à ${NOW} (${FAILED_UNIT}, résultat ${RESULT}). Voir : journalctl -u ${FAILED_UNIT} -n 100"

echo "[BACKUP-ALERT][ERROR] ${MESSAGE}" >&2

if [[ -z "${BACKUP_ALERT_WEBHOOK_URL:-}" ]]; then
  echo "[BACKUP-ALERT][WARN] BACKUP_ALERT_WEBHOOK_URL not set — alert logged to journald only" >&2
  exit 2
fi

# JSON string escaping without jq: quote, backslash, and every control character < 0x20
# (\n, \r, \t by name, the others as \u00XX). Bytes >= 0x80 (UTF-8) pass through.
json_escape() {
  local LC_ALL=C
  local s="$1" out="" c hex i
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "${c}" in
      '"')    out+='\"' ;;
      \\)    out+="\\\\" ;;
      $'\n') out+='\n' ;;
      $'\r') out+='\r' ;;
      $'\t') out+='\t' ;;
      *)
        if [[ "${c}" < $'\x20' ]]; then
          printf -v hex '\\u%04x' "'${c}"
          out+="${hex}"
        else
          out+="${c}"
        fi
        ;;
    esac
  done
  printf '%s' "${out}"
}

build_payload() {
  local to="${BACKUP_ALERT_TO:-}"
  if command -v jq > /dev/null 2>&1 && [[ -z "${BACKUP_ALERT_FORCE_BUILTIN_JSON:-}" ]]; then
    jq -cn --arg text "${MESSAGE}" --arg to "${to}" \
      '{text: $text, content: $text} + (if $to != "" then {to: $to} else {} end)'
    return
  fi
  local msg
  msg="$(json_escape "${MESSAGE}")"
  if [[ -n "${to}" ]]; then
    printf '{"text":"%s","content":"%s","to":"%s"}\n' "${msg}" "${msg}" "$(json_escape "${to}")"
  else
    printf '{"text":"%s","content":"%s"}\n' "${msg}" "${msg}"
  fi
}

# curl config-file quoting: backslash and double quote escaped inside "…".
curl_cfg_quote() {
  local v="$1"
  v="${v//\\/\\\\}"
  v="${v//\"/\\\"}"
  printf '"%s"' "${v}"
}

# Secrets (webhook URL, token) and the payload never go on curl's argv (visible in ps):
# the payload is in a private temp file, the URL and header come from a config on stdin.
umask 077
PAYLOAD_FILE="$(mktemp)"
trap 'rm -f "${PAYLOAD_FILE}"' EXIT
build_payload > "${PAYLOAD_FILE}"

write_curl_config() {
  printf 'url = %s\n' "$(curl_cfg_quote "${BACKUP_ALERT_WEBHOOK_URL}")"
  printf 'header = %s\n' "$(curl_cfg_quote "Content-Type: application/json")"
  if [[ -n "${BACKUP_ALERT_WEBHOOK_TOKEN:-}" ]]; then
    printf 'header = %s\n' "$(curl_cfg_quote "Authorization: Bearer ${BACKUP_ALERT_WEBHOOK_TOKEN}")"
  fi
}

if write_curl_config | curl -fsS --max-time 15 --retry 3 --retry-connrefused --retry-delay 5 \
     -o /dev/null -X POST --data-binary "@${PAYLOAD_FILE}" --config -; then
  echo "[BACKUP-ALERT][INFO] Alert posted to webhook" >&2
else
  echo "[BACKUP-ALERT][ERROR] Webhook call failed — alert only in journald" >&2
  exit 1
fi
