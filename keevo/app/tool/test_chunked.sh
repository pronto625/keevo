#!/usr/bin/env bash
# Story 15.4a — runs a package's tests one folder at a time (the full suite in
# one `flutter test` run is too heavy). Run from the package directory; used by
# `dart run melos test`. Top-level test files run one by one, every
# test/<folder>/ runs as one chunk, except test/features/ which is split per
# feature. Folders without any *_test.dart (e.g. test/helpers/) are skipped.
set -euo pipefail
shopt -s nullglob

if [ ! -d test ]; then
  echo "no test/ directory in $(pwd)"
  exit 0
fi

run() {
  echo "── flutter test $*"
  flutter test --no-pub "$@"
}

# Runs [dir] as one chunk when it contains at least one test file.
run_dir() {
  if [ -n "$(find "$1" -name '*_test.dart' -print -quit)" ]; then
    run "$1"
  else
    echo "── skip $1 (no *_test.dart)"
  fi
}

for f in test/*_test.dart; do
  run "$f"
done
for d in test/*/; do
  if [ "$d" = "test/features/" ]; then
    for fd in test/features/*/; do
      run_dir "$fd"
    done
    for ff in test/features/*_test.dart; do
      run "$ff"
    done
  else
    run_dir "$d"
  fi
done
exit 0
