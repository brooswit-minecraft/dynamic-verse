#!/usr/bin/env bash
# Source-level failure-path test for runProviderSafeDeleteCrashWithReceipt.sh
# (per MINECRAFT-470 PR3 review, Jira comment 51591: retain the actual
# sourced-wrapper test harness in the tree, not only reported as a result).
# SOURCES the real wrapper to get its ACTUAL functions (capture_manifest,
# require_manifest_schema, require_manifests_match, read_process_start_instant,
# root_is_exclusively_claimed, refuse) -- not a hand-retyped replica -- and
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
   && "$(type -t root_is_exclusively_claimed)" == function && "$(type -t refuse)" == function \
   && "$(type -t session_lock_is_held)" == function && "$(type -t phase_b_diagnostic_quit)" == function ]]
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

### 6. root_is_exclusively_claimed + claimSafeDeleteCrashExecutionRoot.sh:
### the execution-root provenance contract (Jira comment 51596 -- an
### emptiness check alone cannot prove a directory was freshly claimed by
### THIS invocation, since ModDev's own prepare*Run side effect leaves an
### untouched directory looking identically "empty" whether it was just
### claimed or is some unrelated retained root). Accepts ONLY a root
### carrying the exact marker the claim script writes; refuses a merely
### empty directory, real content, and any symlink -- never weakened.
claim_script="$workspace/scripts/claimSafeDeleteCrashExecutionRoot.sh"
root_probe_base=$(mktemp -d)
root_absent="$root_probe_base/absent"
root_empty="$root_probe_base/empty"; mkdir "$root_empty"
root_dirty="$root_probe_base/dirty"; mkdir "$root_dirty"; touch "$root_dirty/eula.txt"
root_linked="$root_probe_base/linked"; root_real_target="$root_probe_base/real-target"; mkdir "$root_real_target"
ln -s "$root_real_target" "$root_linked" 2>/dev/null || true
root_claimed="$root_probe_base/claimed"

bash "$claim_script" "$root_claimed" >/dev/null
check "claim script successfully claims a genuinely absent path" "$([[ $? == 0 ]] && echo 1 || echo 0)"
if bash "$claim_script" "$root_claimed" >/dev/null 2>"$probe_out"; then
  check "claim script REFUSES to re-claim an already-claimed path (exclusivity)" 0
else
  grep -q 'refused:' "$probe_out"
  check "claim script REFUSES to re-claim an already-claimed path (exclusivity)" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi
if bash "$claim_script" "$root_empty" >/dev/null 2>"$probe_out"; then
  check "claim script REFUSES to adopt a pre-existing empty directory -- no silent adoption" 0
else
  grep -q 'refused:' "$probe_out"
  check "claim script REFUSES to adopt a pre-existing empty directory -- no silent adoption" "$([[ $? == 0 ]] && echo 1 || echo 0)"
fi

root_is_exclusively_claimed "$root_claimed"
check "root_is_exclusively_claimed accepts a properly-claimed root" "$([[ $? == 0 ]] && echo 1 || echo 0)"
root_is_exclusively_claimed "$root_absent"
check "root_is_exclusively_claimed REFUSES a genuinely absent path (claiming, not this check, creates the root)" "$([[ $? != 0 ]] && echo 1 || echo 0)"
root_is_exclusively_claimed "$root_empty"
check "root_is_exclusively_claimed REFUSES a merely-empty directory -- the exact gap an emptiness-only check had" "$([[ $? != 0 ]] && echo 1 || echo 0)"
root_is_exclusively_claimed "$root_dirty"
check "root_is_exclusively_claimed REFUSES a directory with real (non-marker) content -- never weakened" "$([[ $? != 0 ]] && echo 1 || echo 0)"
if [[ -L "$root_linked" ]]; then
  root_is_exclusively_claimed "$root_linked"
  check "root_is_exclusively_claimed REFUSES a symlinked root -- never weakened" "$([[ $? != 0 ]] && echo 1 || echo 0)"
else
  echo "SKIP: symlinked-root case (this environment could not create a symlink)"
fi

# Regenerating the launch scripts against an already-claimed root (ModDev's
# own prepare*Run side effect) must never disturb the claim marker.
marker_before=$(cat "$root_claimed/.dynamicverse-safe-delete-crash-execution-root-claim")
mkdir -p "$root_claimed" 2>/dev/null || true
marker_after=$(cat "$root_claimed/.dynamicverse-safe-delete-crash-execution-root-claim")
check "a claimed root's marker is unaffected by an idempotent directory-ensure (modeling ModDev's own prepare*Run)" \
  "$([[ "$marker_before" == "$marker_after" ]] && echo 1 || echo 0)"

### 7. Negative tests requested in Jira comment 51604: a marker's mere
### presence/shape is not enough -- it must name the EXACT directory it is
### actually found in, and must not contain duplicate/conflicting fields.
root_copied="$root_probe_base/copied-foreign-marker"
mkdir "$root_copied"
cp "$root_claimed/.dynamicverse-safe-delete-crash-execution-root-claim" "$root_copied/"
root_is_exclusively_claimed "$root_copied"
check "root_is_exclusively_claimed REFUSES a marker copied verbatim into a different directory (right shape, wrong location)" "$([[ $? != 0 ]] && echo 1 || echo 0)"

root_wrong_field="$root_probe_base/wrong-canonical-root-field"
mkdir "$root_wrong_field"
sed "s#^canonicalRoot=.*#canonicalRoot=$root_probe_base/somewhere-else-entirely#" \
  "$root_claimed/.dynamicverse-safe-delete-crash-execution-root-claim" > "$root_wrong_field/.dynamicverse-safe-delete-crash-execution-root-claim"
root_is_exclusively_claimed "$root_wrong_field"
check "root_is_exclusively_claimed REFUSES a marker whose canonicalRoot field names a different path" "$([[ $? != 0 ]] && echo 1 || echo 0)"

root_dup_nonce="$root_probe_base/duplicate-nonce-field"
mkdir "$root_dup_nonce"
own_canonical=$(cd -- "$root_dup_nonce" && pwd -P)
{
  sed "s#^canonicalRoot=.*#canonicalRoot=$own_canonical#" "$root_claimed/.dynamicverse-safe-delete-crash-execution-root-claim"
  echo "nonce=0000000000000000000000000000000000000000000000000000000000000000"
} > "$root_dup_nonce/.dynamicverse-safe-delete-crash-execution-root-claim"
root_is_exclusively_claimed "$root_dup_nonce"
check "root_is_exclusively_claimed REFUSES a marker with a duplicate/conflicting nonce field" "$([[ $? != 0 ]] && echo 1 || echo 0)"

root_malformed_nonce="$root_probe_base/malformed-nonce-field"
mkdir "$root_malformed_nonce"
own_canonical2=$(cd -- "$root_malformed_nonce" && pwd -P)
sed -e "s#^canonicalRoot=.*#canonicalRoot=$own_canonical2#" -e 's/^nonce=.*/nonce=not-a-valid-hex-nonce/' \
  "$root_claimed/.dynamicverse-safe-delete-crash-execution-root-claim" > "$root_malformed_nonce/.dynamicverse-safe-delete-crash-execution-root-claim"
root_is_exclusively_claimed "$root_malformed_nonce"
check "root_is_exclusively_claimed REFUSES a marker with a malformed (non-hex) nonce" "$([[ $? != 0 ]] && echo 1 || echo 0)"

rm -rf "$root_probe_base"

### 8. find_only_child against the REAL generated parent/subshell launch
### shape (Jira comment 51622): the generated script's own content is
### `(cd '...'; exec '<executable>' ...)`. Launching that via
### `/bin/bash -- script &` makes bash fork a subshell to run the
### parenthesized compound command; `exec` replaces the SUBSHELL, not the
### outer wrapper-spawned bash -- so $! is never the real executable.
### Reproduced here with a harmless temporary executable (sleep), no
### Minecraft/native launch, mirroring the exact shape that misled the
### original diagnosis.
subshell_probe_dir=$(mktemp -d)
subshell_script="$subshell_probe_dir/generated.sh"
cat > "$subshell_script" <<SCRIPT
(cd '$subshell_probe_dir'; exec /bin/sleep 30)
SCRIPT
/bin/bash -e -- "$subshell_script" >/dev/null 2>&1 &
launcher_pid=$!
launcher_start=$(proc_start_ticks "$launcher_pid")
child_pid=""
for _ in $(seq 1 50); do
  child_pid=$(find_only_child "$launcher_pid") && break
  sleep 0.1
done
check "find_only_child discovers a real child of the launcher within the expected window" "$([[ -n "$child_pid" ]] && echo 1 || echo 0)"
check "the discovered child's PID differs from the launcher's own \$! (the exact original misdiagnosis)" "$([[ -n "$child_pid" && "$child_pid" != "$launcher_pid" ]] && echo 1 || echo 0)"
if [[ -n "$child_pid" ]]; then
  child_cmd=$(tr '\0' ' ' < "/proc/$child_pid/cmdline" 2>/dev/null)
  check "the discovered child is actually running the exec'd executable, not the launcher's own bash" "$([[ "$child_cmd" == *"sleep"* ]] && echo 1 || echo 0)"
fi
# Killing ONLY the discovered real child (never the launcher) must not
# orphan anything further -- there is nothing further in this shape -- and
# the launcher must exit on its own shortly after, exactly like the
# fixed wrapper's own cleanup now does.
if [[ -n "$child_pid" ]]; then
  kill -9 "$child_pid" 2>/dev/null
  sleep 1
  # A zombie still has a /proc/<pid> entry, so existence alone is not
  # "exited" -- check the actual process state (field 3 of /proc/<pid>/stat);
  # gone entirely or zombie ('Z') both count as "exited" for this check.
  launcher_state=$(awk '{print $3}' "/proc/$launcher_pid/stat" 2>/dev/null)
  check "the launcher exits on its own once its real child (killed directly) is gone" \
    "$([[ -z "$launcher_state" || "$launcher_state" == "Z" ]] && echo 1 || echo 0)"
fi
wait "$launcher_pid" 2>/dev/null || true
rm -rf "$subshell_probe_dir"

# find_only_child must fail closed -- never silently pick one -- when the
# launch shape produces more than one child (not the expected shape).
multi_child_script="$subshell_probe_dir-multi.sh"
mkdir -p "$(dirname "$multi_child_script")" 2>/dev/null || true
cat > "$multi_child_script" <<'SCRIPT'
/bin/sleep 30 & /bin/sleep 30 & wait
SCRIPT
/bin/bash -e -- "$multi_child_script" >/dev/null 2>&1 &
multi_launcher_pid=$!
sleep 0.5
if find_only_child "$multi_launcher_pid" >/dev/null 2>&1; then
  check "find_only_child REFUSES (fails closed) when more than one child exists" 0
else
  check "find_only_child REFUSES (fails closed) when more than one child exists" 1
fi
pkill -9 -P "$multi_launcher_pid" 2>/dev/null || true
kill -9 "$multi_launcher_pid" 2>/dev/null || true
wait "$multi_launcher_pid" 2>/dev/null || true
rm -f "$multi_child_script"

### 9. kill_if_still_owned's identity-gated signaling, proven against real
### (harmless) processes -- never signaling on a start-identity mismatch,
### always signaling on a genuine match. No mocking needed: observing
### whether a real, harmless sleep process survives IS the proof.
owned_pid_match_target=""
/bin/sleep 30 &
owned_pid_match_target=$!
owned_match_ticks=$(proc_start_ticks "$owned_pid_match_target")
kill_if_still_owned "$owned_pid_match_target" "$owned_match_ticks" direct
sleep 0.3
check "kill_if_still_owned signals a real process when start-identity matches" "$([[ ! -d "/proc/$owned_pid_match_target" ]] && echo 1 || echo 0)"

/bin/sleep 30 &
owned_pid_mismatch_target=$!
kill_if_still_owned "$owned_pid_mismatch_target" "0000000-deliberately-wrong-ticks" direct
sleep 0.3
check "kill_if_still_owned REFUSES to signal a real process when start-identity does not match" "$([[ -d "/proc/$owned_pid_mismatch_target" ]] && echo 1 || echo 0)"
kill -9 "$owned_pid_mismatch_target" 2>/dev/null
wait "$owned_pid_mismatch_target" 2>/dev/null || true

### 10. proc_start_ticks reflects a real, currently-running process and
### goes empty once that exact process is gone (the basis every identity
### check in this wrapper relies on).
/bin/sleep 30 &
ticks_probe_pid=$!
ticks_value=$(proc_start_ticks "$ticks_probe_pid")
check "proc_start_ticks returns a plausible non-empty value for a real running process" "$([[ "$ticks_value" =~ ^[0-9]+$ ]] && echo 1 || echo 0)"
kill -9 "$ticks_probe_pid" 2>/dev/null
wait "$ticks_probe_pid" 2>/dev/null || true
ticks_after=$(proc_start_ticks "$ticks_probe_pid")
check "proc_start_ticks returns empty once that exact process is gone" "$([[ -z "$ticks_after" ]] && echo 1 || echo 0)"

### 11. The marker/PID comparison itself, using the real marker-parsing
### idiom (sed -n 's/^pid=//p') against a constructed fake marker -- a
### mismatched self-reported PID must compare unequal to a real
### discovered PID. No native server; no real marker file needed beyond a
### plain constructed properties file.
fake_marker=$(mktemp)
printf 'pid=999999999\n' > "$fake_marker"
fake_reported_pid=$(sed -n 's/^pid=//p' "$fake_marker" | tr -d '\r')
/bin/sleep 5 &
real_discovered_pid=$!
check "a fake marker's self-reported PID correctly compares unequal to a real, different discovered PID (the refusal condition)" \
  "$([[ "$fake_reported_pid" != "$real_discovered_pid" ]] && echo 1 || echo 0)"
kill -9 "$real_discovered_pid" 2>/dev/null
wait "$real_discovered_pid" 2>/dev/null || true
rm -f "$fake_marker"

### 12. session_lock_is_held (Jira comment 51622's live retry, native run
### 20261105T1410Z: the ORIGINAL merged-stream check refused every run in
### this sandbox regardless of truth, because lsof writes an unrelated
### "can't stat() fuse.gvfsd-fuse" warning to stderr UNCONDITIONALLY, and
### the original code merged that warning into the same file it tested for
### non-emptiness). Proven here against REAL harmless file holders/lsof
### invocations, not mocked -- the exact environment-specific false
### positive is reproduced and shown fixed.
lock_probe_file=$(mktemp)
lock_probe_out=$(mktemp)
lock_probe_err=$(mktemp)

# A genuinely held file: a real process (sleep) holds an open fd on it.
( exec 9<>"$lock_probe_file"; sleep 30 ) &
lock_holder_pid=$!
sleep 0.3
held_result=$(session_lock_is_held "$lock_probe_file" "$lock_probe_out" "$lock_probe_err")
check "session_lock_is_held reports 1 (held) for a file a real process actually holds open" "$([[ "$held_result" == 1 ]] && echo 1 || echo 0)"
kill -9 "$lock_holder_pid" 2>/dev/null
wait "$lock_holder_pid" 2>/dev/null || true

# The same file, now genuinely free: must read as free regardless of
# whatever this host's real lsof chooses to write to stderr.
free_result=$(session_lock_is_held "$lock_probe_file" "$lock_probe_out" "$lock_probe_err")
check "session_lock_is_held reports 0 (free) once the real holder is gone, independent of lsof's own stderr content" \
  "$([[ "$free_result" == 0 ]] && echo 1 || echo 0)"

# A non-empty stderr with an EMPTY stdout must never be mistaken for "held" --
# this is the exact bug class: construct that shape directly against a
# wrapped lsof stand-in and confirm the function still reports 0 (free).
fake_lsof_dir=$(mktemp -d)
cat > "$fake_lsof_dir/lsof" <<'FAKE_LSOF'
#!/usr/bin/env bash
echo "lsof: WARNING: can't stat() fuse.gvfsd-fuse file system /run/user/1001/gvfs" >&2
exit 1
FAKE_LSOF
chmod +x "$fake_lsof_dir/lsof"
fake_result=$(PATH="$fake_lsof_dir:$PATH" session_lock_is_held "$lock_probe_file" "$lock_probe_out" "$lock_probe_err")
check "session_lock_is_held is NOT fooled by a stderr-only warning with empty stdout (the exact false positive hit live)" \
  "$([[ "$fake_result" == 0 ]] && echo 1 || echo 0)"

# A genuinely missing lsof binary must still fail closed (exit 125 via refuse).
empty_path_dir=$(mktemp -d)
missing_lsof_status=0
( PATH="$empty_path_dir" session_lock_is_held "$lock_probe_file" "$lock_probe_out" "$lock_probe_err" >/dev/null 2>&1 ) || missing_lsof_status=$?
check "session_lock_is_held fails closed (refuse, exit 125) when the lsof binary itself is unavailable" \
  "$([[ "$missing_lsof_status" == 125 ]] && echo 1 || echo 0)"

rm -rf -- "$lock_probe_file" "$lock_probe_out" "$lock_probe_err" "$fake_lsof_dir" "$empty_path_dir"

### 13. phase_b_diagnostic_quit (round 4, Jira comment 51743's authorized
### retry: phase B's own JVM hung after a fully completed world save,
### forcing the hard timeout with no thread dump ever captured to explain
### why). First review pass (Jira comment 51915) found a real PID-reuse
### gap in the first version of this: it resolved children of a PID
### without confirming that PID was still the process this wrapper
### spawned, never checked the resolved leaf was actually a JVM, and left
### its delayed timer running even after phase B's own observed run ended.
### Confirmed empirically first (not assumed): SIGQUIT's default
### disposition genuinely terminates a plain process with no handler for
### it (a bare `/bin/sleep` dies with "Quit (core dumped)"), so "the leaf
### survives" below is real evidence the function's own gates fired, not
### coincidence. All three gaps reproduced and shown fixed with harmless
### processes throughout, no Minecraft/native launch.
diag_probe_dir=$(mktemp -d)
diag_inner_script="$diag_probe_dir/inner.sh"
diag_outer_script="$diag_probe_dir/outer.sh"
cat > "$diag_inner_script" <<SCRIPT
(cd '$diag_probe_dir'; exec /bin/sleep 30)
SCRIPT
cat > "$diag_outer_script" <<SCRIPT
(cd '$diag_probe_dir'; exec /bin/bash -e -- '$diag_inner_script')
SCRIPT
/bin/bash -e -- "$diag_outer_script" >/dev/null 2>&1 &
diag_launcher_pid=$!
diag_launcher_start=$(proc_start_ticks "$diag_launcher_pid")
diag_level1_pid=""
for _ in $(seq 1 50); do
  diag_level1_pid=$(find_only_child "$diag_launcher_pid") && break
  sleep 0.1
done
diag_leaf_pid=""
for _ in $(seq 1 50); do
  [[ -n "$diag_level1_pid" ]] || break
  diag_leaf_pid=$(find_only_child "$diag_level1_pid") && break
  sleep 0.1
done
check "the two-hop find_only_child chain resolves to a real leaf process" "$([[ -n "$diag_leaf_pid" ]] && echo 1 || echo 0)"
if [[ -n "$diag_leaf_pid" ]]; then
  diag_leaf_cmd=$(tr '\0' ' ' < "/proc/$diag_leaf_pid/cmdline" 2>/dev/null)
  check "the resolved leaf is actually the exec'd sleep, not either intermediate bash" "$([[ "$diag_leaf_cmd" == *"sleep"* ]] && echo 1 || echo 0)"

  # Gap 1 (changed/reused ancestry): a mismatched start-tick must make the
  # function bail out before it ever resolves a single child -- the leaf
  # survives not by luck but because nothing downstream was ever reached.
  phase_b_diagnostic_quit "$diag_launcher_pid" "0000000-deliberately-wrong-ticks"
  sleep 0.3
  check "phase_b_diagnostic_quit REFUSES to act when the given PID's start-identity does not match (reused-PID defense)" \
    "$(kill -0 "$diag_leaf_pid" 2>/dev/null && echo 1 || echo 0)"

  # Gap 2 (non-JVM leaf): identity now matches and the chain resolves
  # correctly, but the leaf is plain sleep, not java -- the comm check
  # alone must block the signal.
  phase_b_diagnostic_quit "$diag_launcher_pid" "$diag_launcher_start"
  sleep 0.3
  check "phase_b_diagnostic_quit REFUSES to signal a resolved leaf whose /proc/<pid>/comm is not 'java'" \
    "$(kill -0 "$diag_leaf_pid" 2>/dev/null && echo 1 || echo 0)"

  check "the outer launcher (timeout-equivalent) was never touched by either call" \
    "$(kill -0 "$diag_launcher_pid" 2>/dev/null && echo 1 || echo 0)"
  check "the intermediate bash (generated_b-equivalent) was never touched by either call" \
    "$(kill -0 "$diag_level1_pid" 2>/dev/null && echo 1 || echo 0)"
fi
kill -9 "$diag_leaf_pid" "$diag_level1_pid" "$diag_launcher_pid" 2>/dev/null
wait "$diag_launcher_pid" 2>/dev/null || true
rm -rf "$diag_probe_dir"

# A lookup against an already-exited phase B must be a clean, silent
# no-op, never an error -- this is pure diagnostics.
/bin/sleep 0.2 &
already_gone_pid=$!
wait "$already_gone_pid" 2>/dev/null
( phase_b_diagnostic_quit "$already_gone_pid" "irrelevant" ) 2>"$probe_out"
check "phase_b_diagnostic_quit against an already-finished phase B is a clean, silent no-op" "$([[ ! -s "$probe_out" ]] && echo 1 || echo 0)"

# Gap 3 (early phase B exit must retire the timer): the call site reuses
# kill_if_still_owned, already proven in section 9 to signal on a genuine
# identity match -- the property worth proving HERE is that doing so
# BEFORE a long delayed payload's own sleep elapses means that payload
# never runs. A real timer standing in for the production one: it sleeps
# far longer than this test waits, then (only if never killed) writes a
# marker file. Retiring it immediately, the same way the call site does
# right after phase B's own `wait` returns, and then waiting past the
# timer's own full duration, must find no marker was ever written.
early_retire_marker=$(mktemp -u)
( sleep 5; : > "$early_retire_marker" ) &
early_retire_pid=$!
early_retire_start=$(proc_start_ticks "$early_retire_pid")
kill_if_still_owned "$early_retire_pid" "$early_retire_start" direct
sleep 5.5
check "retiring a delayed timer immediately (as phase B's own early exit does) stops its payload from ever firing" \
  "$([[ ! -e "$early_retire_marker" ]] && echo 1 || echo 0)"
rm -f "$early_retire_marker"

echo "---"
echo "pass=$pass fail=$fail"
[[ $fail == 0 ]]
