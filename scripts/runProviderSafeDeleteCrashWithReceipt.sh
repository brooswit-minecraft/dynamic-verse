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
#
# Testability: everything below the function/variable definitions is in
# main(), run only when this file is EXECUTED directly, not when it is
# sourced -- so a test script can `source` this exact file to get its real
# functions (not a hand-retyped replica) and exercise failure paths without
# triggering the real orchestration.
#
# Before running this: scripts/claimSafeDeleteCrashExecutionRoot.sh <path>
# must have exclusively claimed the execution root first (this wrapper
# never creates it), then the launch scripts must be regenerated with
# -PsafeDeleteCrashGameDirectory=<same path>, then
# DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT=<same path> must be set for
# this wrapper itself. See root_is_exclusively_claimed below for why an
# emptiness check alone is not accepted.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
source "$workspace/scripts/providerOrdinaryRestartReceiptLib.sh" || exit 125

# Deliberately NOT a fixed default: ModDev's own prepare*Run tasks create
# whatever directory build.gradle's gameDirectory names, as a side effect of
# simply regenerating these launch scripts for review -- the same ordinary
# preparation work this entire review has been doing. If this wrapper also
# defaulted to that same stable path, every routine script regeneration
# would leave this wrapper's own "requires an absent root" guard permanently
# tripped before a real run could ever begin. Requiring an explicit,
# presumably freshly-timestamped override here (matched by regenerating the
# launch scripts with -PsafeDeleteCrashGameDirectory set to the SAME path --
# the existing generated-script contract check below already enforces this
# equality) keeps the stable preparation root and any real execution root
# from ever being the same path. `:-` (not `:?`) so sourcing this file for
# testing never fails on an unset variable under `set -u`; main() itself
# refuses when this is empty.
root="${DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT:-}"
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

# An emptiness check alone cannot prove a directory was freshly claimed by
# THIS invocation rather than being some other retained root that happens
# to be empty for an unrelated reason -- ModDev's own prepare*Run task
# leaves an untouched directory looking identically "empty" either way
# (confirmed empirically). Provenance instead comes from
# claimSafeDeleteCrashExecutionRoot.sh, the ONLY place that ever creates
# this root: it refuses anything but true absence, uses mkdir's own
# atomicity for the actual exclusive claim (no separate check-then-create
# race window), and immediately writes one marker file recording that
# claim. This wrapper never calls mkdir on the root at all -- it only
# verifies that exact marker is still present and well-formed. Confirmed
# empirically that regenerating the launch scripts against an
# already-claimed root (ModDev's own prepare*Run side effect) leaves a
# pre-existing marker file byte-for-byte untouched.
claim_marker_name=.dynamicverse-safe-delete-crash-execution-root-claim
root_is_exclusively_claimed() {
  local path=$1 marker entries canonical_actual
  [[ -d "$path" && ! -L "$path" ]] || return 1
  entries=$(find -P "$path" -mindepth 1 -maxdepth 1 -printf '%f\n' 2>/dev/null) || return 1
  [[ "$entries" == "$claim_marker_name" ]] || return 1
  marker="$path/$claim_marker_name"
  [[ -f "$marker" && ! -L "$marker" ]] || return 1
  # Exactly one of each field -- grep -q alone only proves at least one
  # matching line exists, which a duplicate/conflicting line would still
  # satisfy; counting first catches that.
  [[ $(grep -c '^canonicalRoot=' "$marker") == 1 ]] || return 1
  [[ $(grep -c '^claimedAtUtc=' "$marker") == 1 ]] || return 1
  [[ $(grep -c '^claimedByHost=' "$marker") == 1 ]] || return 1
  [[ $(grep -c '^claimedByPid=' "$marker") == 1 ]] || return 1
  [[ $(grep -c '^nonce=' "$marker") == 1 ]] || return 1
  grep -q '^nonce=[0-9a-f]\{64\}$' "$marker" || return 1
  # The marker must name the EXACT directory it is actually found in --
  # catches a marker copied verbatim into a different directory, which
  # would otherwise still have the right shape.
  canonical_actual=$(cd -- "$path" && pwd -P) || return 1
  [[ "$(sed -n 's/^canonicalRoot=//p' "$marker")" == "$canonical_actual" ]]
}

# Linux /proc start-time ticks (stat field 22): stable across a kill -0 poll
# as long as the kernel has not recycled the PID for a different process.
proc_start_ticks() {
  local pid=$1
  awk '{print $22}' "/proc/$pid/stat" 2>/dev/null
}

# A PID existing (kill -0) is not proof it is still the process this wrapper
# spawned -- the kernel can recycle a PID for something unrelated. Every
# signal, INCLUDING this cleanup/interruption path (not only the primary
# verified-kill path later in the script), must re-check the recorded
# process-start identity before signaling. Without this, a refusal issued
# because identity verification failed would still be defeated by the EXIT
# trap blindly killing whatever now holds that PID.
kill_if_still_owned() {
  local pid=$1 start=$2 as_group=$3 current
  [[ -n "$pid" ]] || return 0
  current=$(proc_start_ticks "$pid") || true
  if [[ -n "$current" && -n "$start" && "$current" == "$start" ]]; then
    if [[ "$as_group" == group ]]; then
      # Phase B is spawned under `timeout` (itself under `setsid`, as its
      # own process-group leader): SIGKILL cannot be forwarded by any
      # process, so killing only the `timeout` PID would silently orphan
      # the actual java process underneath it. Signaling the negative PID
      # (the process GROUP setsid created) reaches both.
      kill -9 -- "-$pid" 2>/dev/null || true
    fi
    kill -9 "$pid" 2>/dev/null || true
  fi
  wait "$pid" 2>/dev/null || true
}

cleanup() {
  kill_if_still_owned "$phase_a_pid" "$phase_a_start" direct
  kill_if_still_owned "$phase_b_pid" "$phase_b_start" group
}

# A trapped INT/TERM handler that returns without exiting lets bash resume
# script execution at the NEXT statement after the interrupted one -- an
# interruption during phase A's wait loop could otherwise fall through into
# launching phase B. Clean up, restore default disposition, then re-deliver
# the signal to ourselves so the process actually terminates (and EXIT's
# own `cleanup` is not invoked a second time).
on_terminating_signal() {
  local sig=$1
  cleanup
  trap - EXIT INT TERM
  kill "-$sig" "$$"
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

# Per-phase structural/identity sanity, re-run every time a manifest is
# captured for that phase: the generated script matches the expected
# contract, and its selected provider jar is the one expected hook-bearing
# corrected artifact (not merely equal to the OTHER phase's, which alone
# would accept two phases sharing the same wrong provider).
verify_phase_sanity() {
  local phase=$1 generated=$2 normal=$3 legacy=$4 vm=$5 program=$6 log4j=$7
  crash_require_generated "$generated" "$normal" "$legacy" "$vm" "$program" "$log4j" "$phase" || refuse "phase $phase generated launch contract invalid"
  local provider provider_hash
  provider=$(ordinary_provider_input "$normal") || refuse "cannot locate exactly one provider jar on phase $phase's classpath"
  provider_hash=$(ordinary_hash "$provider") || refuse "cannot hash phase $phase's selected provider jar"
  [[ "$provider_hash" == "$expected_provider_sha256" ]] || refuse "phase $phase selected provider jar $provider_hash does not match the expected hook-bearing corrected provider $expected_provider_sha256 -- explicitly select -PacceptedDynamicDimensionsJar"
}

# The complete set of retained, content-bound inputs for one phase: the
# provider jar, the full normal AND legacy classpath closures (every entry
# content-hashed, not merely the listing file), all four mod-folder
# directories NeoForge actually loads from, the log4j configuration, the
# program arguments, the exact java executable's own binary, and the VM
# args with only the known phase-specific lines normalized away. Printed
# as one deterministic block so two captures can be compared with a plain
# diff. Every field is produced by an `ordinary_hash`/`ordinary_closure_hash`/
# `ordinary_typed_entry_hash` call, each of which already validates its own
# output is a 64-hex sha256 (see require_manifest_schema below, which relies
# on that).
capture_manifest() {
  local phase=$1 generated=$2 normal=$3 legacy=$4 vm=$5 program=$6 log4j=$7
  local provider provider_hash normal_hash legacy_hash log4j_hash java_hash program_hash
  local main_classes_hash main_resources_hash test_classes_hash test_resources_hash vm_normalized
  provider=$(ordinary_provider_input "$normal") || refuse "cannot locate exactly one provider jar on phase $phase's classpath"
  provider_hash=$(ordinary_hash "$provider") || refuse "cannot hash phase $phase's selected provider jar"
  normal_hash=$(ordinary_closure_hash "$normal") || refuse "cannot hash phase $phase's classpath closure"
  # A hash of the listing file alone only proves the two listings name the
  # same paths, not that the jars at those paths have the same bytes;
  # ordinary_closure_hash resolves and content-hashes every entry named.
  legacy_hash=$(ordinary_closure_hash "$legacy") || refuse "cannot hash phase $phase's legacy classpath contents"
  log4j_hash=$(ordinary_hash "$log4j") || refuse "cannot hash phase $phase's log4j configuration"
  main_classes_hash=$(ordinary_typed_entry_hash "$mod_main_classes") || refuse "cannot hash main classes directory for phase $phase"
  main_resources_hash=$(ordinary_typed_entry_hash "$mod_main_resources") || refuse "cannot hash main resources directory for phase $phase"
  test_classes_hash=$(ordinary_typed_entry_hash "$mod_test_classes") || refuse "cannot hash test classes directory for phase $phase"
  test_resources_hash=$(ordinary_typed_entry_hash "$mod_test_resources") || refuse "cannot hash test resources directory for phase $phase"
  java_hash=$(ordinary_hash /tmp/temurin21/bin/java) || refuse "cannot hash the java executable for phase $phase"
  program_hash=$(ordinary_hash "$program") || refuse "cannot hash phase $phase's program arguments"
  # The only permitted differences between the two VM-args files are the
  # phase-property line itself and the two per-phase-named file references
  # (-Dlog4j2.configurationFile=/-DlegacyClassPath.file=) whose actual
  # *content* equality is already proven by the hashes above.
  vm_normalized=$(grep -vE '^-Ddynamicverse\.providerSafeDeleteCrashPhase=|^-Dlog4j2\.configurationFile=|^-DlegacyClassPath\.file=' "$vm") || refuse "cannot read phase $phase's normalized VM args"
  printf 'provider=%s\nnormalClosure=%s\nlegacyClosure=%s\nlog4j=%s\nmainClasses=%s\nmainResources=%s\ntestClasses=%s\ntestResources=%s\njavaBinary=%s\nprogramArgs=%s\nvmArgsNormalized<<<\n%s\n>>>\n' \
    "$provider_hash" "$normal_hash" "$legacy_hash" "$log4j_hash" "$main_classes_hash" "$main_resources_hash" "$test_classes_hash" "$test_resources_hash" "$java_hash" "$program_hash" "$vm_normalized"
}

# capture_manifest is always invoked as `x=$(capture_manifest ...)`: a
# command substitution. Under `set -uo pipefail` WITHOUT `-e` (errexit),
# an assignment's own exit status is never checked automatically -- if
# capture_manifest calls refuse() internally, that only terminates the
# SUBSHELL the command substitution created; the parent script, lacking
# -e, continues on with whatever partial/empty stdout had been produced.
# Reproduced live: a simulated helper failure inside such a subshell left
# the parent running normally with an empty captured variable. Worse, if
# the SAME failure hits both phase A's and phase B's capture identically,
# two empty manifests compare equal and `require_manifests_match` would
# wrongly report a match. The caller MUST check this function's own exit
# status explicitly (`|| refuse`), and must not trust a non-empty exit
# status alone either -- hence the schema check below.
require_manifest_schema() {
  local label=$1 manifest=$2
  [[ -n "$manifest" ]] || refuse "$label: empty input manifest"
  local key
  for key in provider normalClosure legacyClosure log4j mainClasses mainResources testClasses testResources javaBinary programArgs; do
    [[ $(printf '%s\n' "$manifest" | grep -c "^${key}=[0-9a-f]\{64\}\$") == 1 ]] || refuse "$label: manifest missing or malformed field '$key'"
  done
  [[ $(printf '%s\n' "$manifest" | grep -c '^vmArgsNormalized<<<$') == 1 ]] || refuse "$label: manifest missing the vmArgsNormalized block"
}

# diff's exit status distinguishes "identical" (0) from "differences found"
# (1) from "diff itself failed" (2+, e.g. an unreadable file) -- collapsing
# all non-"found a difference" outcomes into a swallowed `|| true` would
# treat a diff ERROR (empty stdout) as an empty-output success. Fail closed
# on both.
require_manifests_match() {
  local label=$1 before=$2 after=$3 output status
  output=$(diff <(printf '%s' "$before") <(printf '%s' "$after") 2>&1)
  status=$?
  case "$status" in
    0) ;;
    1) refuse "$label: inputs changed ($output)" ;;
    *) refuse "$label: cannot compare manifests (diff exit $status): $output" ;;
  esac
}

# Properties.store (used by the Java fixture to write the marker/result
# files) escapes `:` in VALUES as `\:`, not only in keys -- confirmed
# empirically (a real Properties.store of an ISO instant produced
# `...T01\:12\:10.123Z`). processStartInstant is the one field that
# contains colons; reading it with a plain `sed` extraction, as every
# other (colon-free) marker field is read, leaves the literal backslashes
# in place and breaks `date -d` parsing. Reuses the same proper
# Java-properties reader (ProviderSeededRestartProperties.java, via
# ordinary_java_property) the providerOrdinaryRestart wrapper already
# relies on for exactly this reason, rather than hand-rolling escaping
# rules in bash.
read_process_start_instant() {
  local file=$1
  ordinary_java_property "$workspace" "$file" processStartInstant
}

main() {
  [[ -n "$root" ]] || refuse "DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT must be set explicitly to a root already exclusively claimed via scripts/claimSafeDeleteCrashExecutionRoot.sh -- never the stable preparation root ModDev's prepare*Run tasks create as a side effect of regenerating these launch scripts. Claim a fresh root, regenerate createProviderSafeDeleteCrashPhase{A,B}LaunchScript with -PsafeDeleteCrashGameDirectory set to that SAME path, then set this variable to it."
  server_properties="$root/server.properties"
  world_root="$root/world"
  nonce_file="$root/dynamicverse-crash-delete-expected-nonce.txt"
  marker="$root/dynamicverse-crash-delete-phase-a-marker.properties"
  result="$root/dynamicverse-crash-delete-phase-b-result.properties"

  for tool in sha256sum lsof timeout awk sed grep kill wait mkdir setsid date; do
    command -v "$tool" >/dev/null 2>&1 || refuse "required tool missing before anything is spawned: $tool"
  done
  [[ -d /proc ]] || refuse "/proc is required for owned-process start-identity verification on this platform"

  phase_a_pid=""
  phase_a_start=""
  phase_b_pid=""
  phase_b_start=""
  trap cleanup EXIT
  trap 'on_terminating_signal INT' INT
  trap 'on_terminating_signal TERM' TERM

  verify_phase_sanity A "$generated_a" "$normal_a" "$legacy_a" "$vm_a" "$program_a" "$log4j_a"
  verify_phase_sanity B "$generated_b" "$normal_b" "$legacy_b" "$vm_b" "$program_b" "$log4j_b"
  [[ -f "$generated_a" && -f "$generated_b" ]] || refuse "generated launch scripts missing -- run both createProviderSafeDeleteCrashPhase*LaunchScript tasks first"

  # Captured ONCE, before phase A ever launches, and never recomputed: phase
  # B's manifest is compared against this frozen snapshot rather than against
  # a freshly-recomputed phase A manifest, so a build that changes shared
  # inputs between the two launches cannot have both sides silently agree on
  # the new state while actually running something different from what phase
  # A's marker/receipt will claim. Both the capture's own exit status and its
  # output's schema are checked explicitly -- see require_manifest_schema.
  local pre_phase_a_manifest
  pre_phase_a_manifest=$(capture_manifest A "$generated_a" "$normal_a" "$legacy_a" "$vm_a" "$program_a" "$log4j_a") || refuse "cannot capture the pre-phase-A input manifest"
  require_manifest_schema "pre-phase-A manifest" "$pre_phase_a_manifest"

  root_is_exclusively_claimed "$root" || refuse "requires a root already exclusively claimed via scripts/claimSafeDeleteCrashExecutionRoot.sh $root -- an emptiness check alone cannot prove provenance; never reuse a retained/foreign/merely-empty root"
  [[ ! -e "$receipts" ]] || refuse "requires absent prior receipts"
  mkdir -- "$receipts" || refuse "cannot claim receipts root"
  printf '%s' "$pre_phase_a_manifest" > "$receipts/pre-phase-a.manifest" || refuse "cannot record the pre-phase-A input manifest"

  printf '%s\n' 'eula=true' > "$root/eula.txt" || refuse "cannot write eula acknowledgement"
  printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'level-name=world' > "$server_properties" || refuse "cannot write loopback server properties"

  # This wrapper owns the one expected nonce, written at the game root (not
  # the world root -- the two must never be conflated). Neither phase ever
  # generates or writes it. Reuses the SAME nonce claimSafeDeleteCrashExecutionRoot.sh
  # already recorded in the claim marker, rather than generating a fresh,
  # unrelated one -- carrying the claim's own identity into execution
  # instead of having two unrelated nonces in play for the same root.
  local expected_nonce
  expected_nonce=$(sed -n 's/^nonce=//p' "$root/$claim_marker_name") || refuse "cannot read the claim nonce from the marker"
  [[ -n "$expected_nonce" ]] || refuse "claim marker reports no nonce"
  printf '%s' "$expected_nonce" > "$nonce_file" || refuse "cannot write expected-nonce file"

  ### Phase A: launch, wait for the self-reported marker, verify and kill exactly that PID.
  cd -- "$root" || refuse "cannot cd into disposable root"
  local phase_a_prespawn_epoch
  phase_a_prespawn_epoch=$(date -u +%s)
  /bin/bash -e -- "$generated_a" > "$receipts/phase-a.full.log" 2>&1 &
  phase_a_pid=$!
  phase_a_start=$(proc_start_ticks "$phase_a_pid") || true
  [[ -n "$phase_a_start" ]] || refuse "cannot capture phase A's own process start identity immediately after spawn"
  echo "spawned phase A as PID $phase_a_pid (start ticks $phase_a_start); waiting up to ${marker_timeout_seconds}s for its marker" >&2

  local marker_seen=0 i
  for ((i = 0; i < marker_timeout_seconds; i++)); do
    if [[ -f "$marker" ]]; then marker_seen=1; break; fi
    if [[ "$(proc_start_ticks "$phase_a_pid")" != "$phase_a_start" ]]; then break; fi
    sleep 1
  done
  (( marker_seen )) || refuse "phase A never published its marker within ${marker_timeout_seconds}s"

  local reported_pid reported_nonce reported_game_root reported_start_instant
  reported_pid=$(sed -n 's/^pid=//p' "$marker" | tr -d '\r')
  reported_nonce=$(sed -n 's/^nonce=//p' "$marker" | tr -d '\r')
  reported_game_root=$(sed -n 's/^gameRoot=//p' "$marker" | tr -d '\r')
  reported_start_instant=$(read_process_start_instant "$marker") || refuse "cannot read marker processStartInstant via the properties reader"
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
  local reported_start_epoch now_epoch
  reported_start_epoch=$(date -u -d "$reported_start_instant" +%s 2>/dev/null) || refuse "cannot parse marker processStartInstant '$reported_start_instant'"
  now_epoch=$(date -u +%s)
  (( reported_start_epoch >= phase_a_prespawn_epoch - 2 && reported_start_epoch <= now_epoch + 2 )) || \
    refuse "marker processStartInstant ($reported_start_instant) falls outside the window this wrapper spawned phase A in -- refusing to kill anything"

  # Re-verify both PID and process-start identity immediately before every
  # signal, not only once at marker time -- kill -0 alone only proves a PID
  # exists, not that it is still the same process this wrapper spawned.
  local current_start
  current_start=$(proc_start_ticks "$phase_a_pid") || true
  [[ -n "$current_start" ]] || refuse "phase A PID $phase_a_pid is no longer observable -- refusing to signal"
  [[ "$current_start" == "$phase_a_start" ]] || refuse "phase A PID $phase_a_pid's process-start identity changed -- the kernel may have recycled this PID; refusing to signal"
  echo "verified marker PID $reported_pid matches spawned PID $phase_a_pid with unchanged start identity; sending SIGKILL" >&2
  kill -9 "$phase_a_pid" || refuse "cannot signal phase A PID $phase_a_pid"

  local exited=0 phase_a_exit
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
  # Rechecked immediately before this launch, not only once at the start, so
  # a build run between phases cannot silently change what will actually
  # run -- and compared against the manifest FROZEN before phase A launched,
  # not a freshly-recomputed one, so drift affecting both sides identically
  # still cannot slip through.
  verify_phase_sanity A "$generated_a" "$normal_a" "$legacy_a" "$vm_a" "$program_a" "$log4j_a"
  verify_phase_sanity B "$generated_b" "$normal_b" "$legacy_b" "$vm_b" "$program_b" "$log4j_b"
  local pre_phase_b_manifest
  pre_phase_b_manifest=$(capture_manifest B "$generated_b" "$normal_b" "$legacy_b" "$vm_b" "$program_b" "$log4j_b") || refuse "cannot capture the pre-phase-B input manifest"
  require_manifest_schema "pre-phase-B manifest" "$pre_phase_b_manifest"
  printf '%s' "$pre_phase_b_manifest" > "$receipts/pre-phase-b.manifest" || refuse "cannot record the pre-phase-B input manifest"
  require_manifests_match "phase A/B input manifest" "$pre_phase_a_manifest" "$pre_phase_b_manifest"

  set +e
  # setsid (without -f, so it execs in place rather than forking away) makes
  # this `timeout` invocation its own session/process-group leader, so the
  # actual java process `timeout` later forks inherits that same group --
  # letting cleanup()/refusal paths reach BOTH via `kill -9 -- -$phase_b_pid`
  # even though SIGKILL to `timeout` alone can never be forwarded to its child.
  setsid timeout --kill-after="${phase_b_kill_after_seconds}s" "${phase_b_timeout_seconds}s" /bin/bash -e -- "$generated_b" > "$receipts/phase-b.full.log" 2>&1 &
  phase_b_pid=$!
  phase_b_start=$(proc_start_ticks "$phase_b_pid") || true
  [[ -n "$phase_b_start" ]] || refuse "cannot capture phase B's own process start identity immediately after spawn"
  wait "$phase_b_pid"
  local phase_b_exit=$?
  phase_b_pid=""
  phase_b_start=""
  set -e
  printf '%s\n' "$phase_b_exit" > "$receipts/phase-b.exit"
  # A forced/timed-out phase B (TERM=124, or the hard KILL fallback, commonly
  # 137) is always a failure here, never a clean recovery receipt.
  (( phase_b_exit == 0 )) || refuse "phase B did not exit cleanly (exit $phase_b_exit, 124=TERM-timeout, 137=hard-kill-fallback) -- see $receipts/phase-b.full.log"

  [[ -f "$result" ]] || refuse "phase B produced no result receipt"
  local reported_result_nonce reported_marker_operation reported_result_operation reported_marker_generation reported_result_generation
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
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  main "$@"
fi
