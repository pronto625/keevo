#!/usr/bin/env bash
# Decide which Docker images a deploy workflow must rebuild (backend, dashboard).
#
# Used by deploy-backend.yml (prod) and deploy-staging.yml so both apply the same rules.
#
# Usage: changed-components.sh <after-sha> <workflow-file>
# Env:
#   BACKEND_IMAGE_REF       image:tag redeployed when the backend is NOT rebuilt (e.g. …/backend:latest)
#   DASHBOARD_IMAGE_REF     same for the dashboard
#   FORCE_ALL=true          rebuild everything (manual run)
#   (needs a prior `docker login` to the registry)
# Output: `backend=true|false` and `dashboard=true|false`, appended to $GITHUB_OUTPUT when set,
# printed to stdout otherwise.
#
# Base of the comparison = the commit the CURRENT tag was built from (OCI label
# org.opencontainers.image.revision, set by docker/metadata-action), not the previous push:
# a cancelled or superseded run can never leave a change unbuilt. A component is rebuilt when,
# since that commit, its directory (keevo/<component>/), the calling workflow or this script
# changed — or when the tag, its label or that commit is missing (first deploy, force-push…).
set -euo pipefail

after="${1:?after sha required}"
workflow="${2:?workflow file required}"
script_path=".github/scripts/changed-components.sh"

# Commit an image tag was built from ("" when the tag or the label does not exist).
# Multi-platform tags: one config per platform, all built from the same commit.
image_revision() {
  docker buildx imagetools inspect "$1" --format '{{ json .Image }}' 2>/dev/null \
    | jq -r '[.. | objects | .config? | objects | .Labels? | objects
              | ."org.opencontainers.image.revision"? | strings] | first // ""' 2>/dev/null \
    || true
}

needs_build() { # <component> <image ref>
  local component="$1" ref="$2" rev changed
  if [ "${FORCE_ALL:-false}" = "true" ]; then
    echo "${component}: FORCE_ALL=true → build" >&2; echo true; return
  fi
  rev="$(image_revision "${ref}")"
  if [ -z "${rev}" ]; then
    echo "${component}: ${ref} missing or without revision label → build" >&2; echo true; return
  fi
  if ! git cat-file -e "${rev}^{commit}" 2>/dev/null; then
    echo "${component}: ${ref} built from unknown commit ${rev} → build" >&2; echo true; return
  fi
  changed="$(git diff --name-only "${rev}" "${after}" -- "keevo/${component}/" "${workflow}" "${script_path}")"
  if [ -n "${changed}" ]; then
    printf '%s: changed since %s (%s):\n%s\n' "${component}" "${rev:0:12}" "${ref}" "${changed}" >&2
    echo true
  else
    echo "${component}: unchanged since ${rev:0:12} → reuse ${ref}" >&2
    echo false
  fi
}

backend="$(needs_build backend "${BACKEND_IMAGE_REF:?BACKEND_IMAGE_REF required}")"
dashboard="$(needs_build dashboard "${DASHBOARD_IMAGE_REF:?DASHBOARD_IMAGE_REF required}")"

echo "backend=${backend} dashboard=${dashboard}"
out="${GITHUB_OUTPUT:-/dev/stdout}"
{
  echo "backend=${backend}"
  echo "dashboard=${dashboard}"
} >> "${out}"
