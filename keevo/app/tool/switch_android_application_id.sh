#!/usr/bin/env bash
# v1s-16-18 — Switch the Android application id from the Flutter template
# `com.example.keevo` to the production id `com.keevo.keevo`.
#
# DO NOT RUN before the Firebase step in docs/RELEASE_IDENTITY.md: the
# `google-services` Gradle plugin fails the build when google-services.json has
# no client for the applicationId. A new applicationId is also a different app
# on the device: testers must uninstall the old app and install the new one.
#
# Usage (from anywhere):
#   keevo/app/tool/switch_android_application_id.sh [--dry-run]
#
# Rewrites:
#   - android/app/build.gradle.kts  `namespace` and `applicationId`
#   - MainActivity.kt package, and moves it to kotlin/com/keevo/keevo/
# Idempotent: a second run reports "already switched" and changes nothing.
set -euo pipefail

OLD_ID="com.example.keevo"
NEW_ID="com.keevo.keevo"

DRY_RUN=0
case "${1:-}" in
  "") ;;
  --dry-run) DRY_RUN=1 ;;
  *) echo "usage: $0 [--dry-run]" >&2; exit 64 ;;
esac

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_APP="$APP_DIR/android/app"
GRADLE="$ANDROID_APP/build.gradle.kts"
GOOGLE_SERVICES="$ANDROID_APP/google-services.json"
KOTLIN_ROOT="$ANDROID_APP/src/main/kotlin"
OLD_DIR="$KOTLIN_ROOT/${OLD_ID//.//}"
NEW_DIR="$KOTLIN_ROOT/${NEW_ID//.//}"

die() { echo "ERROR: $*" >&2; exit 1; }
run() {
  if [[ $DRY_RUN -eq 1 ]]; then echo "[dry-run] $*"; else "$@"; fi
}

[[ -f "$GRADLE" ]] || die "missing $GRADLE"

# Portable in-place edit (GNU and BSD/macOS sed).
sed_in_place() {
  local file="${@: -1}"
  run sed -i.bak "$@"
  run rm -f "$file.bak"
}

# --- Guard: google-services.json must declare the new package ----------------
[[ -f "$GOOGLE_SERVICES" ]] || die "missing $GOOGLE_SERVICES"
command -v python3 >/dev/null 2>&1 || die "python3 is required to read $GOOGLE_SERVICES"
set +e
python3 - "$GOOGLE_SERVICES" "$NEW_ID" <<'PY'
import json, sys
path, wanted = sys.argv[1], sys.argv[2]
try:
    with open(path) as f:
        data = json.load(f)
    names = [c["client_info"]["android_client_info"]["package_name"]
             for c in data["client"]]
except Exception as e:  # malformed JSON, null/missing client_info, ...
    print(f"cannot parse {path}: {type(e).__name__}: {e}", file=sys.stderr)
    sys.exit(2)
sys.exit(0 if wanted in names else 1)
PY
guard=$?
set -e
case $guard in
  0) ;;
  2) die "$GOOGLE_SERVICES is not a valid Firebase Android config (see error above). Nothing was changed." ;;
  *)
    if [[ $DRY_RUN -eq 1 ]]; then
      echo "WARNING: $GOOGLE_SERVICES has no Android client for $NEW_ID yet; a real run will refuse until the Firebase step is done (docs/RELEASE_IDENTITY.md)." >&2
    else
      die "$GOOGLE_SERVICES has no Android client for $NEW_ID.
Register $NEW_ID in Firebase and drop the new google-services.json first
(docs/RELEASE_IDENTITY.md, section Android). Nothing was changed."
    fi
    ;;
esac

# --- Already switched? -------------------------------------------------------
gradle_switched=0
if grep -q "applicationId = \"$NEW_ID\"" "$GRADLE" \
  && grep -q "namespace = \"$NEW_ID\"" "$GRADLE" \
  && ! grep -q "\"$OLD_ID\"" "$GRADLE"; then
  gradle_switched=1
fi
activity_switched=0
if [[ -f "$NEW_DIR/MainActivity.kt" && ! -e "$OLD_DIR/MainActivity.kt" ]] \
  && grep -q "^package $NEW_ID\$" "$NEW_DIR/MainActivity.kt"; then
  activity_switched=1
fi
if [[ $gradle_switched -eq 1 && $activity_switched -eq 1 ]]; then
  echo "Already switched to $NEW_ID — nothing to do."
  exit 0
fi

# --- build.gradle.kts --------------------------------------------------------
if [[ $gradle_switched -eq 0 ]]; then
  for key in namespace applicationId; do
    grep -q "$key = \"$OLD_ID\"" "$GRADLE" \
      || grep -q "$key = \"$NEW_ID\"" "$GRADLE" \
      || die "unexpected $key in $GRADLE (expected \"$OLD_ID\" or \"$NEW_ID\"). Nothing was changed."
  done
  sed_in_place \
    -e "s/namespace = \"$OLD_ID\"/namespace = \"$NEW_ID\"/" \
    -e "s/applicationId = \"$OLD_ID\"/applicationId = \"$NEW_ID\"/" \
    "$GRADLE"
  if [[ $DRY_RUN -eq 0 ]]; then
    grep -q "namespace = \"$NEW_ID\"" "$GRADLE" \
      && grep -q "applicationId = \"$NEW_ID\"" "$GRADLE" \
      || die "$GRADLE was not fully rewritten; check namespace/applicationId by hand"
  fi
  echo "build.gradle.kts: namespace + applicationId -> $NEW_ID"
fi

# --- MainActivity.kt ---------------------------------------------------------
if [[ $activity_switched -eq 0 ]]; then
  if [[ -f "$OLD_DIR/MainActivity.kt" ]]; then
    [[ -e "$NEW_DIR/MainActivity.kt" ]] \
      && die "both $OLD_DIR/MainActivity.kt and $NEW_DIR/MainActivity.kt exist; resolve by hand"
    run mkdir -p "$NEW_DIR"
    if git -C "$APP_DIR" ls-files --error-unmatch "$OLD_DIR/MainActivity.kt" >/dev/null 2>&1; then
      run git -C "$APP_DIR" mv "$OLD_DIR/MainActivity.kt" "$NEW_DIR/MainActivity.kt"
    else
      run mv "$OLD_DIR/MainActivity.kt" "$NEW_DIR/MainActivity.kt"
    fi
    # Remove the now-empty com/example/keevo, then com/example (portable).
    if [[ $DRY_RUN -eq 1 ]]; then
      echo "[dry-run] remove empty folders above $OLD_DIR"
    else
      d="$OLD_DIR"
      while [[ "$d" != "$KOTLIN_ROOT" ]] && rmdir "$d" 2>/dev/null; do
        d="$(dirname "$d")"
      done
    fi
  fi
  [[ $DRY_RUN -eq 1 || -f "$NEW_DIR/MainActivity.kt" ]] || die "MainActivity.kt not found"
  target="$NEW_DIR/MainActivity.kt"
  if [[ $DRY_RUN -eq 1 && ! -f "$target" ]]; then target="$OLD_DIR/MainActivity.kt"; fi
  sed_in_place "s/^package $OLD_ID\$/package $NEW_ID/" "$target"
  echo "MainActivity.kt: package $NEW_ID, moved to ${NEW_DIR#"$APP_DIR"/}"
fi

if [[ $DRY_RUN -eq 1 ]]; then
  echo "Dry run complete — no file changed."
else
  echo "Done. Next: flutter run on Android, then tell testers to uninstall the old app (see docs/RELEASE_IDENTITY.md)."
fi
