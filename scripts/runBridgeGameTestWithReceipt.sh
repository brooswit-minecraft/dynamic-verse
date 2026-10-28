#!/usr/bin/env bash
# Preparation-only companion for the generated ModDev launch script. Do not run
# without a separate serialized runtime assignment.
set -u

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_root="$workspace/run/bridgeGameTest-controlled-omitted-seed-create"
expected_root="$workspace/run/bridgeGameTest-controlled-omitted-seed-create"
generated="$workspace/build/moddev/runBridgeGameTest.sh"
receipt="$workspace/build/moddev/bridgeGameTest-omitted-seed-create.original-exit"
wrapper_receipt="$workspace/build/moddev/bridgeGameTest-omitted-seed-create.wrapper-exit"
full_log="$workspace/build/moddev/bridgeGameTest-omitted-seed-create.full.log"

finish() {
  status=$?
  if [[ ! -e "$wrapper_receipt" ]]; then
    printf '%s\n' "$status" > "$wrapper_receipt"
  fi
}
trap finish EXIT

if [[ ! -f "$generated" || ! -d "$run_root" || -L "$run_root" || "$(readlink -f -- "$run_root")" != "$expected_root" ]]; then
  exit 125
fi
if [[ -n "$(find "$run_root" -mindepth 1 -print -quit)" || -e "$receipt" || -e "$wrapper_receipt" || -e "$full_log" ]]; then
  exit 125
fi
if ! cd -- "$run_root"; then
  exit 125
fi

set +e
/bin/bash "$generated" 2>&1 | tee "$full_log"
status=${PIPESTATUS[0]}
set -e
printf '%s\n' "$status" > "$receipt"
exit "$status"
