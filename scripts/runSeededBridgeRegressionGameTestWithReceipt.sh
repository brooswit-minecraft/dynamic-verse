#!/usr/bin/env bash
# Reviewed launch wrapper for the isolated seeded-bridge regression profile.
# It is intentionally not a preparation task and requires a separate runtime
# assignment before it may be invoked.
set -euo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_root="$workspace/run/seededBridgeRegression-isolated"
expected_root="$workspace/run/seededBridgeRegression-isolated"
generated="$workspace/build/moddev/runSeededBridgeRegressionGameTest.sh"
receipt_root="$workspace/build/moddev/seededBridgeRegression-isolated-receipts"
generated_receipt="$receipt_root/generated.exit"
tee_receipt="$receipt_root/tee.exit"
wrapper_receipt="$receipt_root/wrapper.exit"
full_log="$receipt_root/full.log"

refuse() { printf '%s\n' "$1" >&2; exit "$2"; }

if [[ ! -f "$generated" || ! -d "$run_root" || -L "$run_root" || "$(readlink -f -- "$run_root")" != "$expected_root" ]]; then
  refuse "seeded bridge run root or generated script is invalid" 125
fi
if ! cd -- "$run_root"; then
  refuse "cannot cd to seeded bridge run root" 126
fi
if ! root_entry=$(find . -mindepth 1 -maxdepth 1 -print -quit); then
  refuse "cannot inspect seeded bridge run root" 127
fi
if [[ "$(pwd -P)" != "$expected_root" || -n "$root_entry" ]]; then
  refuse "seeded bridge run root is noncanonical or nonempty" 127
fi
if [[ -e "$receipt_root" || -L "$receipt_root" ]]; then
  refuse "seeded bridge receipt root already exists" 128
fi
if ! mkdir -- "$receipt_root"; then
  refuse "cannot claim seeded bridge receipt root" 129
fi

set +e
/bin/bash -e -- "$generated" 2>&1 | tee "$full_log"
statuses=("${PIPESTATUS[@]}")
set -e
status=0
if [[ "${statuses[0]}" -ne 0 ]]; then status="${statuses[0]}"; elif [[ "${statuses[1]}" -ne 0 ]]; then status="${statuses[1]}"; fi
receipt_failed=0
printf '%s\n' "${statuses[0]}" > "$generated_receipt" || receipt_failed=1
printf '%s\n' "${statuses[1]}" > "$tee_receipt" || receipt_failed=1
if [[ "$receipt_failed" -ne 0 ]]; then status=130; fi
printf '%s\n' "$status" > "$wrapper_receipt" || exit 131
exit "$status"
