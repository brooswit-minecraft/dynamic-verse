#!/usr/bin/env bash
# Preparation-only companion for the generated ModDev launch script. Do not run
# without a separate serialized runtime assignment.
set -u

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_root="$workspace/run/bridgeGameTest-controlled-observation-minecraft"
expected_root="$workspace/run/bridgeGameTest-controlled-observation-minecraft"
generated="$workspace/build/moddev/runBridgeGameTest.sh"
receipt="$workspace/build/moddev/bridgeGameTest-minecraft.original-exit"

if [[ ! -f "$generated" || ! -d "$run_root" || "$(readlink -f -- "$run_root")" != "$expected_root" ]]; then
  exit 125
fi
if ! cd -- "$run_root"; then
  exit 125
fi

set +e
/bin/bash "$generated"
status=$?
set -e
printf '%s\n' "$status" > "$receipt"
exit "$status"
