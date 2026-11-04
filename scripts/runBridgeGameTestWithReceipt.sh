#!/usr/bin/env bash
# Preparation-only companion for the generated ModDev launch script. Do not run
# without a separate serialized runtime assignment.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_root="$workspace/run/bridgeGameTest-controlled-pending-create-lookup-fixed"
expected_root="$workspace/run/bridgeGameTest-controlled-pending-create-lookup-fixed"
generated="$workspace/build/moddev/runBridgeGameTest.sh"
receipt_root="$workspace/build/moddev/bridgeGameTest-controlled-pending-create-lookup-fixed-receipts"
receipt="$receipt_root/bridgeGameTest.original-exit"
tee_receipt="$receipt_root/bridgeGameTest.tee-exit"
wrapper_receipt="$receipt_root/bridgeGameTest.wrapper-exit"
full_log="$receipt_root/bridgeGameTest.full.log"

refuse() { printf '%s\n' "$1" >&2; exit "$2"; }

if [[ ! -f "$generated" || ! -d "$run_root" || -L "$run_root" || "$(readlink -f -- "$run_root")" != "$expected_root" ]]; then
  refuse "controlled GameTest root or generated script is invalid" 125
fi
if ! cd -- "$run_root"; then
  refuse "cannot cd to controlled GameTest root" 126
fi
if [[ "$(pwd -P)" != "$expected_root" || -n "$(find . -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
  refuse "controlled GameTest root is noncanonical or nonempty" 127
fi
if [[ -e "$receipt_root" || -L "$receipt_root" ]]; then
  refuse "controlled GameTest receipt root already exists" 128
fi
if ! mkdir -- "$receipt_root"; then
  refuse "cannot claim controlled GameTest receipt root" 129
fi

set +e
/bin/bash -e -- "$generated" 2>&1 | tee "$full_log"
statuses=("${PIPESTATUS[@]}")
set -e
status=0
if [[ "${statuses[0]}" -ne 0 ]]; then status="${statuses[0]}"; elif [[ "${statuses[1]}" -ne 0 ]]; then status="${statuses[1]}"; fi
receipt_failed=0
printf '%s\n' "${statuses[0]}" > "$receipt" || receipt_failed=1
printf '%s\n' "${statuses[1]}" > "$tee_receipt" || receipt_failed=1
if [[ "$receipt_failed" -ne 0 ]]; then status=130; fi
printf '%s\n' "$status" > "$wrapper_receipt" || exit 131
exit "$status"
