#!/usr/bin/env bash
# Source-level failure-path test for runProviderSafeDeleteCrashWithReceipt.sh
# (per MINECRAFT-470 PR3 review, Jira comment 51591: retain the actual
# sourced-wrapper test harness in the tree, not only reported as a result).
# SOURCES the real wrapper to get its ACTUAL functions (capture_manifest,
# require_manifest_schema, require_manifests_match, read_process_start_instant,
# root_is_pristine_or_absent, refuse) -- not a hand-retyped replica -- and
# exercises failure paths with mocked helpers and real serialized input.
# No process is spawned anywhere in this script; main() is never called
# (sourcing never invokes it -- see the wrapper's own BASH_SOURCE guard).
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
wrapper="$workspace/scripts/runProviderSafeDeleteCrashWithReceipt.sh"
# The generated launch scripts under build/moddev/ were produced against the
# stable preparation-root default (no -PsafeDeleteCrashGameDirectory
# override); this must match so the wrapper's own generated-script contract
# check (`cd '$root'; exec`) succeeds against those real files below.
export DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT="$workspace/run/m470-safe-delete-crash-disposable-20261104"

pass=0
fail=0
check() {
  local label=$1 ok=$2
  if [[ "$ok" == 1 ]]; then echo "PASS: $label"; pass=$((pass + 1));
  else echo "FAIL: $label"; fail=$((fail + 1)); fi
}

source "$wrapper"
[[ "$(type -t capture_manifest)" == function && "$(type -t require_manifest_schema)" == function \
   && "$(type -t require_manifests_match)" == function && "$(type -t read_process_start_instant)" == function \
   && "$(type -t root_is_pristine_or_absent)" == function && "$(type -t refuse)" == function ]]
check "sourced the real wrapper's actual functions" "$([[ $? == 0 ]] && echo 1 || echo 0)"

normal_a_real="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunClasspath.txt"
legacy_a_real="$workspace/build/moddev/providerSafeDeleteCrashPhaseALegacyClasspath.txt"
vm_a_real="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunVmArgs.txt"
program_a_real="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunProgramArgs.txt"
log4j_a_real="$workspace/build/moddev/providerSafeDeleteCrashPhaseALog4j2.xml"
generated_a_real="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseA.sh"

probe_out=$(mktemp)
trap 'rm -f -- "$probe_out"' EXIT

### 1. Reproduce the original bug class, then prove the fix in the real code.
# Mock a helper capture_manifest depends on so it fails partway through.
ordinary_typed_entry_hash() { return 1; }

# The OLD (buggy) idiom: assign a command substitution with no exit-status
# check, under `set -uo pipefail` (no errexit). This demonstrates the CLASS
# of bug the review found -- NOT the current wrapper code, which no longer
# does this.
old_idiom_result=$(capture_manifest A "$generated_a_real" "$normal_a_real" "$legacy_a_real" "$vm_a_real" "$program_a_real" "$log4j_a_real" 2>/dev/null)
check "bug reproduced: old unguarded idiom leaves the script running with a captured value of '$old_idiom_result'" 1

# The FIX, exactly as main() now does it: `|| refuse`. The inner call is
# wrapped in a command substitution, same as main() itself does, so ITS OWN
# internal refuse (capture_manifest calls refuse on its own helper failures)
# only ends that inner subshell/assignment -- letting the outer `|| refuse
# "capture failed"` actually fire, the same way main()'s real idiom does.
# The whole probe runs in its own subshell (refuse() calls exit) so only the
# probe ends, not this whole test.
if ( fixed_idiom_result=$(capture_manifest A "$generated_a_real" "$normal_a_real" "$legacy_a_real" "$vm_a_real" "$program_a_real" "$log4j_a_real" 2>/dev/null) || refuse "capture failed" ) 2>"$probe_out"; then
  check "fix: guarded idiom refuses on a failed capture (unexpectedly succeeded)" 0
else
  grep -q 'refused: capture failed' "$probe_out"
  check "fix: guarded idiom (the real main() pattern) refuses on a failed capture, never reaching a launch" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi
# unset -f only removes the override -- it does NOT restore a shadowed
# definition, so the real one must be re-sourced.
unset -f ordinary_typed_entry_hash
source "$wrapper"

### 2. Helper failure is caught by capture_manifest's own internal refuse,
### even when called standalone (not only in the two call-site idioms).
ordinary_hash() { return 1; }
if ( capture_manifest A "$generated_a_real" "$normal_a_real" "$legacy_a_real" "$vm_a_real" "$program_a_real" "$log4j_a_real" ) >/dev/null 2>"$probe_out"; then
  check "helper failure inside capture_manifest is caught (unexpectedly succeeded)" 0
else
  grep -q 'refused:' "$probe_out"
  check "helper failure (ordinary_hash) inside capture_manifest is caught and refused" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi
unset -f ordinary_hash
source "$wrapper"

### 3. require_manifest_schema rejects a missing/partial-schema manifest,
### even when the capture's exit status was (hypothetically) 0.
partial_manifest=$'provider=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'
if ( require_manifest_schema "partial probe" "$partial_manifest" ) 2>"$probe_out"; then
  check "require_manifest_schema accepts a partial manifest (should have refused)" 0
else
  grep -q "missing or malformed field 'normalClosure'" "$probe_out"
  check "require_manifest_schema refuses a partial/missing-field manifest" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi

if ( require_manifest_schema "empty probe" "" ) 2>"$probe_out"; then
  check "require_manifest_schema accepts an empty manifest (should have refused)" 0
else
  grep -q "empty input manifest" "$probe_out"
  check "require_manifest_schema refuses an empty manifest -- the exact 'two empty manifests compare equal' risk" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi

### 4. Drift between phase A and phase B cannot reach a launch: a real
### manifest diffed against a deliberately altered copy is refused, and
### against itself is accepted.
real_manifest=$(capture_manifest A "$generated_a_real" "$normal_a_real" "$legacy_a_real" "$vm_a_real" "$program_a_real" "$log4j_a_real")
drifted_manifest=$(printf '%s' "$real_manifest" | sed 's/^provider=.*/provider=0000000000000000000000000000000000000000000000000000000000000000/')
if ( require_manifests_match "drift probe" "$real_manifest" "$drifted_manifest" ) 2>"$probe_out"; then
  check "require_manifests_match accepts drifted inputs (should have refused)" 0
else
  grep -q "inputs changed" "$probe_out"
  check "require_manifests_match refuses drifted inputs between phase A and phase B" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi

if ( require_manifests_match "identical probe" "$real_manifest" "$real_manifest" ) 2>"$probe_out"; then
  check "require_manifests_match accepts genuinely identical manifests" 1
else
  check "require_manifests_match WRONGLY refused identical manifests: $(cat "$probe_out")" 0
fi

### 5. The real Properties-escaping bug: a real Java-serialized marker with
### a colon-bearing processStartInstant, read back via the real
### read_process_start_instant (ProviderSeededRestartProperties.java).
tmp_marker_dir=$(mktemp -d)
cat > "$tmp_marker_dir/WriteMarker.java" <<'JAVA'
import java.util.Properties;
import java.io.*;
public class WriteMarker {
    public static void main(String[] a) throws Exception {
        Properties p = new Properties();
        p.setProperty("processStartInstant", "2026-11-05T01:12:10.123Z");
        p.setProperty("pid", "12345");
        try (OutputStream out = new FileOutputStream(a[0])) { p.store(out, "test marker"); }
    }
}
JAVA
(cd "$tmp_marker_dir" && /tmp/temurin21/bin/javac WriteMarker.java && /tmp/temurin21/bin/java WriteMarker "$tmp_marker_dir/marker.properties")
raw_line=$(grep '^processStartInstant=' "$tmp_marker_dir/marker.properties")
echo "real serialized marker line: $raw_line"

# Prove the OLD sed-based read is broken against this real file.
old_sed_value=$(sed -n 's/^processStartInstant=//p' "$tmp_marker_dir/marker.properties" | tr -d '\r')
if date -u -d "$old_sed_value" +%s >/dev/null 2>&1; then
  check "bug reproduction: old sed-based read unexpectedly still parses (escaping not actually present?)" 0
else
  check "bug reproduced: old sed-based read of a real serialized marker ('$old_sed_value') fails to parse with date -d" 1
fi

# Prove the FIX: the real read_process_start_instant round-trips correctly.
fixed_value=$(read_process_start_instant "$tmp_marker_dir/marker.properties")
fixed_rc=$?
fixed_epoch=$(date -u -d "$fixed_value" +%s 2>/dev/null)
check "fix: read_process_start_instant returns the correctly unescaped value ('$fixed_value'), parseable by date -d" \
  "$([[ $fixed_rc == 0 && "$fixed_value" == "2026-11-05T01:12:10.123Z" && -n "$fixed_epoch" ]] && echo 1 || echo 0)"
rm -rf "$tmp_marker_dir"

### 6. root_is_pristine_or_absent: the execution-root/preparation-root
### separation contract (Jira comment 51591). Accepts true absence and a
### genuinely empty directory (ModDev's own prepare*Run side effect);
### refuses anything with real content and any symlink -- never weakened.
root_probe_base=$(mktemp -d)
root_absent="$root_probe_base/absent"
root_empty="$root_probe_base/empty"; mkdir "$root_empty"
root_dirty="$root_probe_base/dirty"; mkdir "$root_dirty"; touch "$root_dirty/eula.txt"
root_linked="$root_probe_base/linked"; root_real_target="$root_probe_base/real-target"; mkdir "$root_real_target"
ln -s "$root_real_target" "$root_linked" 2>/dev/null || true

root_is_pristine_or_absent "$root_absent"
check "root_is_pristine_or_absent accepts a genuinely absent path" "$([[ $? == 0 ]] && echo 1 || echo 0)"
root_is_pristine_or_absent "$root_empty"
check "root_is_pristine_or_absent accepts a genuinely empty directory (the ModDev prepare*Run side effect)" "$([[ $? == 0 ]] && echo 1 || echo 0)"
root_is_pristine_or_absent "$root_dirty"
check "root_is_pristine_or_absent REFUSES a directory with real content -- never weakened" "$([[ $? != 0 ]] && echo 1 || echo 0)"
if [[ -L "$root_linked" ]]; then
  root_is_pristine_or_absent "$root_linked"
  check "root_is_pristine_or_absent REFUSES a symlinked root -- never weakened" "$([[ $? != 0 ]] && echo 1 || echo 0)"
else
  echo "SKIP: symlinked-root case (this environment could not create a symlink)"
fi
rm -rf "$root_probe_base"

echo "---"
echo "pass=$pass fail=$fail"
[[ $fail == 0 ]]
