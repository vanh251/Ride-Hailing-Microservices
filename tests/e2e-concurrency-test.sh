#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_CONCURRENCY_LIB_ONLY=1 source "$ROOT/scripts/e2e-concurrency.sh"

assert_rejected() {
  local description="$1"
  shift
  if "$@" >/dev/null 2>&1; then
    printf 'FAIL: accepted %s\n' "$description" >&2
    exit 1
  fi
}

assert_rejected 'malformed JSON' json_field '{"tripId":"abc"' tripId
assert_rejected 'failed trip creation' require_created 500 '{"error":"failed"}' passenger-a
assert_rejected 'two assigned trips' validate_snapshot ASSIGNED driver-a ASSIGNED driver-a BUSY trip-a 2 trip-a trip-b driver-a 1
assert_rejected 'wrong database owner' validate_snapshot ASSIGNED driver-a SEARCHING '' BUSY trip-b 2 trip-a trip-b driver-a 1

validate_snapshot ASSIGNED driver-a SEARCHING '' BUSY trip-a 2 trip-a trip-b driver-a 1
printf 'PASS: concurrency demo contract checks\n'
