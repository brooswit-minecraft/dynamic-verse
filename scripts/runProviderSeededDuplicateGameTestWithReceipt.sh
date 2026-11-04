#!/usr/bin/env bash
# Future runtime wrapper for the isolated native seeded-duplicate fixture.
# It requires separately reviewed launch inputs and a serialized assignment.
set -euo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
run_root="$workspace/run/providerSeededDuplicate-native-retry"
expected_root="$workspace/run/providerSeededDuplicate-native-retry"
generated="$workspace/build/moddev/runProviderSeededDuplicateGameTest.sh"
receipt_root="$workspace/build/moddev/providerSeededDuplicate-native-retry-receipts"
generated_receipt="$receipt_root/generated.exit"
tee_receipt="$receipt_root/tee.exit"
wrapper_receipt="$receipt_root/wrapper.exit"
full_log="$receipt_root/full.log"

refuse() { printf '%s\n' "$1" >&2; exit "$2"; }
if [[ ! -f "$generated" || ! -d "$run_root" || -L "$run_root" || "$(readlink -f -- "$run_root")" != "$expected_root" ]]; then
  refuse "seeded duplicate run root or generated script is invalid" 125
fi
if ! cd -- "$run_root"; then refuse "cannot cd to seeded duplicate root" 126; fi
if ! root_entry=$(find . -mindepth 1 -maxdepth 1 -print -quit); then refuse "cannot inspect seeded duplicate root" 127; fi
if [[ "$(pwd -P)" != "$expected_root" || -n "$root_entry" ]]; then refuse "seeded duplicate root is noncanonical or nonempty" 127; fi
if [[ -e "$receipt_root" || -L "$receipt_root" ]]; then refuse "seeded duplicate receipt root already exists" 128; fi
if ! mkdir -- "$receipt_root"; then refuse "cannot claim seeded duplicate receipt root" 129; fi

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
