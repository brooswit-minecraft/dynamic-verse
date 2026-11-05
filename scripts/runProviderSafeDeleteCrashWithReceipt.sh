#!/usr/bin/env bash
# Prepared for review; NOT yet executed destructively (per MINECRAFT-470 PR3
# technical plan review rounds). Orchestrates the two-JVM safe-delete crash
# fixture: owns and verifies a single expected nonce, enforces the generated
# launch scripts' root/phase/provider-identity/closure contract (reusing the
# established providerOrdinaryRestart hash/closure helpers, not merely
# checking that a script file exists), launches Phase A, waits for its
# own-PID marker, re-verifies ownership immediately before sending SIGKILL to
# exactly that PID (never name-based/broad), confirms real exit and a free
# session.lock (failing closed if lsof is unavailable, not skipping), then
# launches Phase B under its own timeout and requires both a clean exit and
# every result-receipt assertion, cross-checked against Phase A's recorded
# operation identity.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
source "$workspace/scripts/providerOrdinaryRestartReceiptLib.sh" || exit 125

root="$workspace/run/m470-safe-delete-crash-disposable-20261104"
server_properties="$root/server.properties"
world_root="$root/world"
nonce_file="$root/dynamicverse-crash-delete-expected-nonce.txt"
marker="$world_root/dynamicverse-crash-delete-phase-a-marker.properties"
result="$world_root/dynamicverse-crash-delete-phase-b-result.properties"
generated_a="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseA.sh"
generated_b="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseB.sh"
normal_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunClasspath.txt"
normal_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBRunClasspath.txt"
vm_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunVmArgs.txt"
vm_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBRunVmArgs.txt"
program_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseARunProgramArgs.txt"
program_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBRunProgramArgs.txt"
log4j_a="$workspace/build/moddev/providerSafeDeleteCrashPhaseALog4j2.xml"
log4j_b="$workspace/build/moddev/providerSafeDeleteCrashPhaseBLog4j2.xml"
receipts="$workspace/build/moddev/providerSafeDeleteCrash-receipts"
# The exact hook-bearing corrected provider jar (TestOnlySaveFailureHook
# added on top of the admission-order fix); NOT the older SUPERFLAT-credited
# artifact and NOT the stale repository default.
expected_provider_sha256="4c4d70a90498fded6dcb8488a114597a5ed0052a8c721e888637d3e0bb29f041"
marker_timeout_seconds=120
exit_timeout_seconds=30
phase_b_timeout_seconds=120

refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }

phase_a_pid=""
cleanup() {
  # Any refusal path after spawning Phase A must not leave its blocked JVM
  # alive. Only ever signals the exact PID this wrapper itself spawned.
  if [[ -n "$phase_a_pid" ]] && kill -0 "$phase_a_pid" 2>/dev/null; then
    kill -9 "$phase_a_pid" 2>/dev/null || true
    wait "$phase_a_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

crash_require_generated() {
  local generated=$1 normal=$2 vm=$3 program=$4 log4j=$5 phase=$6
  [[ -f "$generated" && ! -L "$generated" && -f "$normal" && ! -L "$normal" && -f "$vm" && ! -L "$vm" && -f "$program" && ! -L "$program" && -f "$log4j" && ! -L "$log4j" ]] || return 1
  [[ $(grep -Foc -- "cd '$root'; exec" "$generated") == 1 ]] || return 1
  [[ $(grep -Foc -- "'@$normal'" "$generated") == 1 && $(grep -Foc -- "'@$vm'" "$generated") == 1 && $(grep -Foc -- "'@$program'" "$generated") == 1 ]] || return 1
  [[ $(grep -Fxc -- "-DlegacyClassPath.file=${vm%RunVmArgs.txt}LegacyClasspath.txt" "$vm") == 1 && $(grep -Fxc -- "-Dlog4j2.configurationFile=$log4j" "$vm") == 1 ]] || return 1
  [[ $(grep -Fxc -- "-Ddynamicverse.providerSafeDeleteCrashPhase=$phase" "$vm") == 1 && $(grep -Fc -- '-Ddynamicverse.providerSafeDeleteCrashPhase=' "$vm") == 1 ]] || return 1
  [[ $(grep -Fc -- '-Dneoforge.enabledGameTestNamespaces=' "$vm") == 0 ]] || return 1
  [[ $(head -n 1 -- "$normal") == -classpath ]] || return 1
}

[[ -f "$generated_a" && -f "$generated_b" ]] || refuse "generated launch scripts missing -- run both createProviderSafeDeleteCrashPhase*LaunchScript tasks first"
crash_require_generated "$generated_a" "$normal_a" "$vm_a" "$program_a" "$log4j_a" A || refuse "phase A generated launch contract invalid"
crash_require_generated "$generated_b" "$normal_b" "$vm_b" "$program_b" "$log4j_b" B || refuse "phase B generated launch contract invalid"

provider_a=$(ordinary_provider_input "$normal_a") || refuse "cannot locate exactly one provider jar on phase A's classpath"
provider_b=$(ordinary_provider_input "$normal_b") || refuse "cannot locate exactly one provider jar on phase B's classpath"
provider_a_hash=$(ordinary_hash "$provider_a") || refuse "cannot hash phase A's selected provider jar"
provider_b_hash=$(ordinary_hash "$provider_b") || refuse "cannot hash phase B's selected provider jar"
[[ "$provider_a_hash" == "$expected_provider_sha256" ]] || refuse "phase A selected provider jar $provider_a_hash does not match the expected hook-bearing corrected provider $expected_provider_sha256 -- compilation/script generation alone does not prove this; explicitly select -PacceptedDynamicDimensionsJar"
[[ "$provider_b_hash" == "$expected_provider_sha256" ]] || refuse "phase B selected provider jar $provider_b_hash does not match the expected hook-bearing corrected provider $expected_provider_sha256"

closure_a=$(ordinary_closure_hash "$normal_a") || refuse "cannot hash phase A's classpath closure"
closure_b=$(ordinary_closure_hash "$normal_b") || refuse "cannot hash phase B's classpath closure"
[[ "$closure_a" == "$closure_b" ]] || refuse "phase A and phase B classpath closures differ ($closure_a vs $closure_b) -- both phases must run the exact same build/provider inputs"

[[ ! -e "$root" && ! -L "$root" ]] || refuse "requires an absent non-symlink disposable root -- never reuse a retained profile"
[[ ! -e "$receipts" ]] || refuse "requires absent prior receipts"
mkdir -- "$root" || refuse "cannot claim fresh disposable root"
mkdir -- "$receipts" || refuse "cannot claim receipts root"

printf '%s\n' 'eula=true' > "$root/eula.txt" || refuse "cannot write eula acknowledgement"
printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'level-name=world' > "$server_properties" || refuse "cannot write loopback server properties"

# This wrapper owns the one expected nonce; neither phase ever generates or
# writes it. Both phases, and this wrapper, must agree on it exactly.
expected_nonce=$(sha256sum <<<"$RANDOM$RANDOM$$$(date +%s%N)" | awk '{print $1}') || refuse "cannot derive an expected nonce"
printf '%s' "$expected_nonce" > "$nonce_file" || refuse "cannot write expected-nonce file"

### Phase A: launch, wait for the self-reported marker, verify and kill exactly that PID.
cd -- "$root" || refuse "cannot cd into disposable root"
/bin/bash -e -- "$generated_a" > "$receipts/phase-a.full.log" 2>&1 &
phase_a_pid=$!
echo "spawned phase A as PID $phase_a_pid; waiting up to ${marker_timeout_seconds}s for its marker" >&2

marker_seen=0
for ((i = 0; i < marker_timeout_seconds; i++)); do
  if [[ -f "$marker" ]]; then marker_seen=1; break; fi
  if ! kill -0 "$phase_a_pid" 2>/dev/null; then break; fi
  sleep 1
done
(( marker_seen )) || refuse "phase A never published its marker within ${marker_timeout_seconds}s"

reported_pid=$(sed -n 's/^pid=//p' "$marker" | tr -d '\r')
reported_nonce=$(sed -n 's/^nonce=//p' "$marker" | tr -d '\r')
reported_root=$(sed -n 's/^root=//p' "$marker" | tr -d '\r')
[[ "$reported_pid" =~ ^[0-9]+$ ]] || refuse "marker reports no valid PID"
[[ "$reported_pid" == "$phase_a_pid" ]] || refuse "marker PID $reported_pid does not match the exact JVM this wrapper spawned ($phase_a_pid) -- refusing to kill anything"
[[ "$reported_nonce" == "$expected_nonce" ]] || refuse "marker nonce does not match this wrapper's own expected nonce"
[[ "$reported_root" == "$root" ]] || refuse "marker root does not match this wrapper's disposable root"
[[ "$(sed -n 's/^durablePendingVerified=//p' "$marker" | tr -d '\r')" == "true" ]] || refuse "marker does not confirm the durable PENDING intent was verified before publication"

# Re-verify ownership immediately before every signal, not only once.
kill -0 "$phase_a_pid" 2>/dev/null || refuse "phase A PID $phase_a_pid is no longer the process this wrapper spawned"
echo "verified marker PID $reported_pid matches spawned PID $phase_a_pid; sending SIGKILL" >&2
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

command -v lsof >/dev/null 2>&1 || refuse "lsof is unavailable -- lock-release validation fails closed rather than silently skipping"
lsof -- "$world_root/session.lock" > "$receipts/phase-a.session-lock-check.log" 2>&1
[[ ! -s "$receipts/phase-a.session-lock-check.log" ]] || refuse "a process still holds the world session.lock after phase A's forced exit"

### Phase B: an independent, timeout-bounded launch against the same disposable root.
set +e
timeout "${phase_b_timeout_seconds}s" /bin/bash -e -- "$generated_b" > "$receipts/phase-b.full.log" 2>&1
phase_b_exit=$?
set -e
printf '%s\n' "$phase_b_exit" > "$receipts/phase-b.exit"
(( phase_b_exit != 124 )) || refuse "phase B did not exit within ${phase_b_timeout_seconds}s -- timed out, not confirmed clean"
(( phase_b_exit == 0 )) || refuse "phase B did not exit cleanly (exit $phase_b_exit) -- see $receipts/phase-b.full.log"

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
grep -q '^unrelatedLeaseAcquired=true$' "$result" || refuse "phase B result does not confirm the unrelated verse's lease was acquired"
grep -q '^unrelatedSentinelIntact=true$' "$result" || refuse "phase B result does not confirm the unrelated verse's resolved sentinel"
grep -q '^targetStorageIntact=true$' "$result" || refuse "phase B result does not confirm the target's on-disk storage survived"

echo "phase A forced-kill exit: $phase_a_exit (expected/recorded separately, not a success signal)" >&2
echo "phase B clean exit: $phase_b_exit" >&2
echo "all phase B result assertions confirmed, cross-checked against phase A's recorded operation $reported_marker_operation" >&2
