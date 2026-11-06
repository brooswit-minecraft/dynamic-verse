#!/usr/bin/env bash
# One bounded, explicitly authorized native invocation of
# providerSafeDeleteScenariosGameTest (Jira comment 51612: success,
# occupied refusal, busy refusal, exact-target FAILED_SAVE, in one
# serialized runtime). Mirrors runProviderCreateGameTestWithReceipt.sh's
# established pattern exactly: requires the generated script and an
# exclusively-claimed, genuinely empty run root (never the stable
# preparation root), refuses if any receipt already exists, cds into the
# claimed root, runs the generated script under `tee` so the log is
# captured even on failure, and records the real exit status separately
# from the wrapper's own.
set -u

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_root="$workspace/run/m470-safe-delete-scenarios-NATIVE-20261105T0304Z"
expected_root="$run_root"
generated="$workspace/build/moddev/runProviderSafeDeleteScenariosGameTest.sh"
receipt="$run_root.original-exit"
wrapper_receipt="$run_root.wrapper-exit"
full_log="$run_root.full.log"
claim_marker="$run_root/.dynamicverse-safe-delete-crash-execution-root-claim"

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
# The run root must contain EXACTLY the claim marker -- never adopt an
# unknown/foreign/dirty directory, and never reuse the stable preparation
# root (which this script does not even reference).
if [[ "$(find "$run_root" -mindepth 1 -maxdepth 1 -printf '%f\n' 2>/dev/null)" != "$(basename -- "$claim_marker")" ]]; then
  exit 125
fi
if [[ -e "$receipt" || -e "$wrapper_receipt" || -e "$full_log" ]]; then
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
