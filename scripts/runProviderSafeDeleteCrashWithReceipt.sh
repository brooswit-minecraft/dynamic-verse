#!/usr/bin/env bash
# Prepared for review; NOT yet executed destructively (per MINECRAFT-470 PR3
# technical plan review rounds). Orchestrates the two-JVM safe-delete crash
# fixture: owns and verifies a single expected nonce, enforces the generated
# launch scripts' root/phase/provider-identity/closure contract (reusing the
# established providerOrdinaryRestart hash/closure helpers), rechecks that
# contract immediately before each launch so a build between phases cannot
# silently change the evidence, launches Phase A, waits for its own-PID
# marker, re-verifies both PID and process-start identity immediately before
# sending SIGKILL to exactly that PID (never name-based/broad), confirms
# real exit and a free session.lock (failing closed if lsof is unavailable),
# then launches Phase B under a hard-kill-bounded timeout and requires both
# a clean exit and every result-receipt assertion, cross-checked against
# Phase A's recorded operation identity. A forced Phase B exit is always a
# failure here, never treated as a clean recovery receipt.
#
# gameRoot vs worldRoot: `root` below is the --gameDir the JVM is actually
# launched with (what this wrapper writes eula.txt/server.properties/the
# nonce/marker/result files under); `world_root` is the Minecraft save
# directory `root/world` (level-name=world), used only for the session.lock
# check and as the known parent of dimension storage. The fixture's own
# ProviderSafeDeleteCrashFixtureRootBindingTest (JUnit, no native launch)
# exercises the Java-side binding between these two directly.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
source "$workspace/scripts/providerOrdinaryRestartReceiptLib.sh" || exit 125

root="$workspace/run/m470-safe-delete-crash-disposable-20261104"
server_properties="$root/server.properties"
world_root="$root/world"
nonce_file="$root/dynamicverse-crash-delete-expected-nonce.txt"
marker="$root/dynamicverse-crash-delete-phase-a-marker.properties"
result="$root/dynamicverse-crash-delete-phase-b-result.properties"
generated_a="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseA.sh"
generated_b="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseB.sh"
normal_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunClasspath.txt"
normal_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBRunClasspath.txt"
legacy_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseALegacyClasspath.txt"
legacy_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBLegacyClasspath.txt"
vm_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunVmArgs.txt"
vm_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBRunVmArgs.txt"
program_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunProgramArgs.txt"
program_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBRunProgramArgs.txt"
log4j_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseALog4j2.xml"
log4j_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBLog4j2.xml"
# The exact four directories ModDev wires into -Dfml.modFolders= for every
# run of this source set; both phases launch from the same workspace, so
# these paths are identical for A and B -- what the closure check below
# actually proves is that their on-disk CONTENT did not change between
# phases (a rebuild in between would change these hashes).
mod_main_classes="$workspace/build/classes/java/main"
mod_main_resources="$workspace/build/resources/main"
mod_test_classes="$workspace/build/classes/java/test"
mod_test_resources="$workspace/build/resources/test"
receipts="$workspace/build/moddev/providerSafeDeleteCrash-receipts"
# The exact hook-bearing corrected provider jar (TestOnlySaveFailureHook
# added on top of the admission-order fix); NOT the older SUPERFLAT-credited
# artifact and NOT the stale repository default.
expected_provider_sha256="4c4d70a90498fded6dcb8488a114597a5ed0052a8c721e888637d3e0bb29f041"
marker_timeout_seconds=120
exit_timeout_seconds=30
phase_b_timeout_seconds=120
phase_b_kill_after_seconds=10

refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }

for tool in sha256sum lsof timeout awk sed grep kill wait mkdir setsid date; do
  command -v "$tool" >/dev/null 2>&1 || refuse "required tool missing before anything is spawned: $tool"
done
[[ -d /proc ]] || refuse "/proc is required for owned-process start-identity verification on this platform"

phase_a_pid=""
phase_b_pid=""
cleanup() {
  # Any refusal/interruption path after spawning a phase must not leave its
  # process alive. Phase A execs java directly over its own spawned PID, so
  # signaling that PID is sufficient. Phase B is spawned under `timeout`
  # (itself under `setsid`, as its own process-group leader): SIGKILL
  # cannot be forwarded by any process, so killing only the `timeout` PID
  # would silently orphan the actual java process underneath it. Signaling
  # the negative PID (the process GROUP setsid created) reaches both.
  if [[ -n "$phase_a_pid" ]] && kill -0 "$phase_a_pid" 2>/dev/null; then
    kill -9 "$phase_a_pid" 2>/dev/null || true
    wait "$phase_a_pid" 2>/dev/null || true
  fi
  if [[ -n "$phase_b_pid" ]] && kill -0 "$phase_b_pid" 2>/dev/null; then
    kill -9 -- "-$phase_b_pid" 2>/dev/null || true
    kill -9 "$phase_b_pid" 2>/dev/null || true
    wait "$phase_b_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT INT TERM

# Linux /proc start-time ticks (stat field 22): stable across a kill -0 poll
# as long as the kernel has not recycled the PID for a different process.
proc_start_ticks() {
  local pid=$1
  awk '{print $22}' "/proc/$pid/stat" 2>/dev/null
}

crash_require_generated() {
  local generated=$1 normal=$2 legacy=$3 vm=$4 program=$5 log4j=$6 phase=$7
  [[ -f "$generated" && ! -L "$generated" && -f "$normal" && ! -L "$normal" && -f "$legacy" && ! -L "$legacy" && -f "$vm" && ! -L "$vm" && -f "$program" && ! -L "$program" && -f "$log4j" && ! -L "$log4j" ]] || return 1
  [[ $(grep -Foc -- "cd '$root'; exec" "$generated") == 1 ]] || return 1
  [[ $(grep -Foc -- "'@$normal'" "$generated") == 1 && $(grep -Foc -- "'@$vm'" "$generated") == 1 && $(grep -Foc -- "'@$program'" "$generated") == 1 ]] || return 1
  [[ $(grep -Fxc -- "-DlegacyClassPath.file=$legacy" "$vm") == 1 && $(grep -Fxc -- "-Dlog4j2.configurationFile=$log4j" "$vm") == 1 ]] || return 1
  [[ $(grep -Fxc -- "-Ddynamicverse.providerSafeDeleteCrashPhase=$phase" "$vm") == 1 && $(grep -Fc -- '-Ddynamicverse.providerSafeDeleteCrashPhase=' "$vm") == 1 ]] || return 1
  [[ $(grep -Fc -- '-Dneoforge.enabledGameTestNamespaces=' "$vm") == 0 ]] || return 1
  [[ $(head -n 1 -- "$normal") == -classpath ]] || return 1
  # The exact java executable this generated script will exec, bound into
  # the contract rather than merely assumed.
  [[ $(grep -Foc -- "exec '/tmp/temurin21/bin/java'" "$generated") == 1 ]] || return 1
  # The exact four mod-folder directories this run actually loads classes
  # and resources from -- not merely trusted to be the ones hashed below.
  [[ $(grep -Foc -- "'-Dfml.modFolders=dynamicverse%%$mod_main_classes:dynamicverse%%$mod_main_resources:dynamicverse%%$mod_test_classes:dynamicverse%%$mod_test_resources'" "$generated") == 1 ]] || return 1
}

# Rechecked immediately before each launch, not only once at the start, so
# a build run between phases cannot silently change what will actually run.
verify_input_contract() {
  crash_require_generated "$generated_a" "$normal_a" "$legacy_a" "$vm_a" "$program_a" "$log4j_a" A || refuse "phase A generated launch contract invalid"
  crash_require_generated "$generated_b" "$normal_b" "$legacy_b" "$vm_b" "$program_b" "$log4j_b" B || refuse "phase B generated launch contract invalid"

  local provider_a provider_b provider_a_hash provider_b_hash closure_a closure_b legacy_closure_a legacy_closure_b vm_diff
  provider_a=$(ordinary_provider_input "$normal_a") || refuse "cannot locate exactly one provider jar on phase A's classpath"
  provider_b=$(ordinary_provider_input "$normal_b") || refuse "cannot locate exactly one provider jar on phase B's classpath"
  provider_a_hash=$(ordinary_hash "$provider_a") || refuse "cannot hash phase A's selected provider jar"
  provider_b_hash=$(ordinary_hash "$provider_b") || refuse "cannot hash phase B's selected provider jar"
  [[ "$provider_a_hash" == "$expected_provider_sha256" ]] || refuse "phase A selected provider jar $provider_a_hash does not match the expected hook-bearing corrected provider $expected_provider_sha256 -- explicitly select -PacceptedDynamicDimensionsJar"
  [[ "$provider_b_hash" == "$expected_provider_sha256" ]] || refuse "phase B selected provider jar $provider_b_hash does not match the expected hook-bearing corrected provider $expected_provider_sha256"

  closure_a=$(ordinary_closure_hash "$normal_a") || refuse "cannot hash phase A's classpath closure"
  closure_b=$(ordinary_closure_hash "$normal_b") || refuse "cannot hash phase B's classpath closure"
  [[ "$closure_a" == "$closure_b" ]] || refuse "phase A and phase B classpath closures differ ($closure_a vs $closure_b)"

  # A hash of the listing file alone only proves the two listings name the
  # same paths, not that the jars at those paths have the same bytes.
  # ordinary_closure_hash resolves and content-hashes every entry the
  # listing names (recursively, for directory entries).
  legacy_closure_a=$(ordinary_closure_hash "$legacy_a") || refuse "cannot hash phase A's legacy classpath contents"
  legacy_closure_b=$(ordinary_closure_hash "$legacy_b") || refuse "cannot hash phase B's legacy classpath contents"
  [[ "$legacy_closure_a" == "$legacy_closure_b" ]] || refuse "phase A and phase B legacy classpath contents differ"

  local mod_main_classes_hash_a mod_main_resources_hash_a mod_test_classes_hash_a mod_test_resources_hash_a
  local mod_main_classes_hash_b mod_main_resources_hash_b mod_test_classes_hash_b mod_test_resources_hash_b
  mod_main_classes_hash_a=$(ordinary_typed_entry_hash "$mod_main_classes") || refuse "cannot hash main classes directory before phase A"
  mod_main_resources_hash_a=$(ordinary_typed_entry_hash "$mod_main_resources") || refuse "cannot hash main resources directory before phase A"
  mod_test_classes_hash_a=$(ordinary_typed_entry_hash "$mod_test_classes") || refuse "cannot hash test classes directory before phase A"
  mod_test_resources_hash_a=$(ordinary_typed_entry_hash "$mod_test_resources") || refuse "cannot hash test resources directory before phase A"
  mod_main_classes_hash_b=$(ordinary_typed_entry_hash "$mod_main_classes") || refuse "cannot hash main classes directory before phase B"
  mod_main_resources_hash_b=$(ordinary_typed_entry_hash "$mod_main_resources") || refuse "cannot hash main resources directory before phase B"
  mod_test_classes_hash_b=$(ordinary_typed_entry_hash "$mod_test_classes") || refuse "cannot hash test classes directory before phase B"
  mod_test_resources_hash_b=$(ordinary_typed_entry_hash "$mod_test_resources") || refuse "cannot hash test resources directory before phase B"
  [[ "$mod_main_classes_hash_a" == "$mod_main_classes_hash_b" ]] || refuse "main classes directory content changed between phase A and phase B verification"
  [[ "$mod_main_resources_hash_a" == "$mod_main_resources_hash_b" ]] || refuse "main resources directory content changed between phase A and phase B verification"
  [[ "$mod_test_classes_hash_a" == "$mod_test_classes_hash_b" ]] || refuse "test classes directory content changed between phase A and phase B verification"
  [[ "$mod_test_resources_hash_a" == "$mod_test_resources_hash_b" ]] || refuse "test resources directory content changed between phase A and phase B verification"
  printf 'mainClasses=%s\nmainResources=%s\ntestClasses=%s\ntestResources=%s\n' \
    "$mod_main_classes_hash_a" "$mod_main_resources_hash_a" "$mod_test_classes_hash_a" "$mod_test_resources_hash_a" \
    > "$receipts/mod-folder-hashes.txt" 2>/dev/null || true

  local log4j_hash_a log4j_hash_b
  log4j_hash_a=$(ordinary_hash "$log4j_a") || refuse "cannot hash phase A's log4j configuration"
  log4j_hash_b=$(ordinary_hash "$log4j_b") || refuse "cannot hash phase B's log4j configuration"
  [[ "$log4j_hash_a" == "$log4j_hash_b" ]] || refuse "phase A and phase B log4j configurations differ"

  # The only permitted differences between the two VM-args files are the
  # phase-property line itself and the two per-phase-named file references
  # (-Dlog4j2.configurationFile=/-DlegacyClassPath.file=) whose actual
  # *content* equality is already proven by the hashes just above.
  vm_diff=$(diff <(grep -vE '^-Ddynamicverse\.providerSafeDeleteCrashPhase=|^-Dlog4j2\.configurationFile=|^-DlegacyClassPath\.file=' "$vm_a") \
                 <(grep -vE '^-Ddynamicverse\.providerSafeDeleteCrashPhase=|^-Dlog4j2\.configurationFile=|^-DlegacyClassPath\.file=' "$vm_b")) || true
  [[ -z "$vm_diff" ]] || refuse "phase A and phase B VM args differ beyond the expected phase-only differences: $vm_diff"
}

verify_input_contract
[[ -f "$generated_a" && -f "$generated_b" ]] || refuse "generated launch scripts missing -- run both createProviderSafeDeleteCrashPhase*LaunchScript tasks first"

[[ ! -e "$root" && ! -L "$root" ]] || refuse "requires an absent non-symlink disposable root -- never reuse a retained profile"
[[ ! -e "$receipts" ]] || refuse "requires absent prior receipts"
mkdir -- "$root" || refuse "cannot claim fresh disposable root"
mkdir -- "$receipts" || refuse "cannot claim receipts root"

printf '%s\n' 'eula=true' > "$root/eula.txt" || refuse "cannot write eula acknowledgement"
printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'level-name=world' > "$server_properties" || refuse "cannot write loopback server properties"

# This wrapper owns the one expected nonce, written at the game root (not
# the world root -- the two must never be conflated). Neither phase ever
# generates or writes it.
expected_nonce=$(sha256sum <<<"$RANDOM$RANDOM$$$(date +%s%N)" | awk '{print $1}') || refuse "cannot derive an expected nonce"
printf '%s' "$expected_nonce" > "$nonce_file" || refuse "cannot write expected-nonce file"

### Phase A: launch, wait for the self-reported marker, verify and kill exactly that PID.
cd -- "$root" || refuse "cannot cd into disposable root"
phase_a_prespawn_epoch=$(date -u +%s)
/bin/bash -e -- "$generated_a" > "$receipts/phase-a.full.log" 2>&1 &
phase_a_pid=$!
phase_a_start=$(proc_start_ticks "$phase_a_pid") || true
[[ -n "$phase_a_start" ]] || refuse "cannot capture phase A's own process start identity immediately after spawn"
echo "spawned phase A as PID $phase_a_pid (start ticks $phase_a_start); waiting up to ${marker_timeout_seconds}s for its marker" >&2

marker_seen=0
for ((i = 0; i < marker_timeout_seconds; i++)); do
  if [[ -f "$marker" ]]; then marker_seen=1; break; fi
  if [[ "$(proc_start_ticks "$phase_a_pid")" != "$phase_a_start" ]]; then break; fi
  sleep 1
done
(( marker_seen )) || refuse "phase A never published its marker within ${marker_timeout_seconds}s"

reported_pid=$(sed -n 's/^pid=//p' "$marker" | tr -d '\r')
reported_nonce=$(sed -n 's/^nonce=//p' "$marker" | tr -d '\r')
reported_game_root=$(sed -n 's/^gameRoot=//p' "$marker" | tr -d '\r')
reported_start_instant=$(sed -n 's/^processStartInstant=//p' "$marker" | tr -d '\r')
[[ "$reported_pid" =~ ^[0-9]+$ ]] || refuse "marker reports no valid PID"
[[ "$reported_pid" == "$phase_a_pid" ]] || refuse "marker PID $reported_pid does not match the exact JVM this wrapper spawned ($phase_a_pid) -- refusing to kill anything"
[[ "$reported_nonce" == "$expected_nonce" ]] || refuse "marker nonce does not match this wrapper's own expected nonce"
[[ "$reported_game_root" == "$root" ]] || refuse "marker gameRoot does not match this wrapper's disposable root"
[[ "$(sed -n 's/^durablePendingVerified=//p' "$marker" | tr -d '\r')" == "true" ]] || refuse "marker does not confirm the durable PENDING intent was verified before publication"

# Cross-check the JVM's own self-reported wall-clock start instant (written
# by ProcessHandle.current().info().startInstant(), otherwise never
# consumed) against this wrapper's independent observation window -- an
# identity signal from a different clock source than the /proc start-ticks
# check below, catching a PID whose kernel-relative ticks happen to match
# but whose actual wall-clock start lies outside the window this wrapper
# could have spawned it in.
[[ -n "$reported_start_instant" && "$reported_start_instant" != unknown ]] || refuse "marker does not report a usable processStartInstant"
reported_start_epoch=$(date -u -d "$reported_start_instant" +%s 2>/dev/null) || refuse "cannot parse marker processStartInstant '$reported_start_instant'"
now_epoch=$(date -u +%s)
(( reported_start_epoch >= phase_a_prespawn_epoch - 2 && reported_start_epoch <= now_epoch + 2 )) || \
  refuse "marker processStartInstant ($reported_start_instant) falls outside the window this wrapper spawned phase A in -- refusing to kill anything"

# Re-verify both PID and process-start identity immediately before every
# signal, not only once at marker time -- kill -0 alone only proves a PID
# exists, not that it is still the same process this wrapper spawned.
current_start=$(proc_start_ticks "$phase_a_pid") || true
[[ -n "$current_start" ]] || refuse "phase A PID $phase_a_pid is no longer observable -- refusing to signal"
[[ "$current_start" == "$phase_a_start" ]] || refuse "phase A PID $phase_a_pid's process-start identity changed -- the kernel may have recycled this PID; refusing to signal"
echo "verified marker PID $reported_pid matches spawned PID $phase_a_pid with unchanged start identity; sending SIGKILL" >&2
kill -9 "$phase_a_pid" || refuse "cannot signal phase A PID $phase_a_pid"

exited=0
for ((i = 0; i < exit_timeout_seconds; i++)); do
  if ! kill -0 "$phase_a_pid" 2>/dev/null; then exited=1; break; fi
  sleep 1
done
(( exited )) || refuse "phase A PID $phase_a_pid did not exit within ${exit_timeout_seconds}s of SIGKILL"
wait "$phase_a_pid" 2>/dev/null; phase_a_exit=$?
phase_a_pid=""   # already reaped; cleanup trap must not touch it again
# Expected and recorded separately from a successful exit status: this PID
# was forcibly killed, so its exit status is not evidence of success or
# failure, only confirmation that it actually terminated.
printf '%s\n' "$phase_a_exit" > "$receipts/phase-a.forced-kill-exit"

lsof -- "$world_root/session.lock" > "$receipts/phase-a.session-lock-check.log" 2>&1
[[ ! -s "$receipts/phase-a.session-lock-check.log" ]] || refuse "a process still holds the world session.lock after phase A's forced exit"

### Phase B: an independent, timeout-bounded launch against the same disposable root.
verify_input_contract   # rechecked immediately before this launch too.
set +e
# setsid (without -f, so it execs in place rather than forking away) makes
# this `timeout` invocation its own session/process-group leader, so the
# actual java process `timeout` later forks inherits that same group --
# letting cleanup()/refusal paths reach BOTH via `kill -9 -- -$phase_b_pid`
# even though SIGKILL to `timeout` alone can never be forwarded to its child.
setsid timeout --kill-after="${phase_b_kill_after_seconds}s" "${phase_b_timeout_seconds}s" /bin/bash -e -- "$generated_b" > "$receipts/phase-b.full.log" 2>&1 &
phase_b_pid=$!
wait "$phase_b_pid"
phase_b_exit=$?
phase_b_pid=""
set -e
printf '%s\n' "$phase_b_exit" > "$receipts/phase-b.exit"
# A forced/timed-out phase B (TERM=124, or the hard KILL fallback, commonly
# 137) is always a failure here, never a clean recovery receipt.
(( phase_b_exit == 0 )) || refuse "phase B did not exit cleanly (exit $phase_b_exit, 124=TERM-timeout, 137=hard-kill-fallback) -- see $receipts/phase-b.full.log"

[[ -f "$result" ]] || refuse "phase B produced no result receipt"
reported_result_nonce=$(sed -n 's/^nonce=//p' "$result" | tr -d '\r')
reported_marker_operation=$(sed -n 's/^operationId=//p' "$marker" | tr -d '\r')
reported_result_operation=$(sed -n 's/^operationId=//p' "$result" | tr -d '\r')
reported_marker_generation=$(sed -n 's/^targetGeneration=//p' "$marker" | tr -d '\r')
reported_result_generation=$(sed -n 's/^targetGeneration=//p' "$result" | tr -d '\r')
[[ "$reported_result_nonce" == "$expected_nonce" ]] || refuse "phase B result nonce does not match this wrapper's expected nonce"
[[ -n "$reported_marker_operation" && "$reported_result_operation" == "$reported_marker_operation" ]] || refuse "phase B result operationId does not match phase A's recorded operation -- refusing a mismatched receipt"
[[ -n "$reported_marker_generation" && "$reported_result_generation" == "$reported_marker_generation" ]] || refuse "phase B result targetGeneration does not match phase A's recorded snapshot"
grep -q '^targetQuarantined=true$' "$result" || refuse "phase B result does not confirm the target was quarantined"
grep -q '^targetRejectionCompleted=true$' "$result" || refuse "phase B result does not confirm an explicit completed rejection with no lease"
grep -q '^targetNeverReloaded=true$' "$result" || refuse "phase B result does not confirm the target was never reloaded"
grep -q '^targetOperationInterruptedPostRecovery=true$' "$result" || refuse "phase B result does not confirm the exact operation persisted as the restart-interrupted terminal post-recovery"
grep -q '^unrelatedLeaseAcquired=true$' "$result" || refuse "phase B result does not confirm the unrelated verse's lease was acquired"
grep -q '^unrelatedSentinelIntact=true$' "$result" || refuse "phase B result does not confirm the unrelated verse's resolved sentinel"
grep -q '^targetStorageIntact=true$' "$result" || refuse "phase B result does not confirm the target's on-disk storage survived"
grep -q '^targetContentIntact=true$' "$result" || refuse "phase B result does not confirm the target's saved region content survived unchanged"

echo "phase A forced-kill exit: $phase_a_exit (expected/recorded separately, not a success signal)" >&2
echo "phase B clean exit: $phase_b_exit" >&2
echo "all phase B result assertions confirmed, cross-checked against phase A's recorded operation $reported_marker_operation" >&2
