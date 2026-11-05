#!/usr/bin/env bash
# Prepared for review; NOT yet executed destructively (per MINECRAFT-470 PR3
# technical plan review). Orchestrates the two-JVM safe-delete crash fixture:
# launches Phase A, waits for its own-PID marker, kills exactly that verified
# PID (never a name-based/broad kill), then launches Phase B and requires it
# to exit cleanly. Narrower and less exhaustively hashed than the established
# providerOrdinaryRestart convention (scripts/providerOrdinaryRestartReceiptLib.sh)
# -- intentionally, pending review of this first draft before any run.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
root="$workspace/run/m470-safe-delete-crash-disposable-20261104"
server_properties="$root/server.properties"
world_root="$root/world"
marker="$world_root/dynamicverse-crash-delete-phase-a-marker.properties"
result="$world_root/dynamicverse-crash-delete-phase-b-result.properties"
generated_a="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseA.sh"
generated_b="$workspace/build/moddev/runProviderSafeDeleteCrashPhaseB.sh"
receipts="$workspace/build/moddev/providerSafeDeleteCrash-receipts"
marker_timeout_seconds=120
exit_timeout_seconds=30

refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }

[[ -f "$generated_a" && -f "$generated_b" ]] || refuse "generated launch scripts missing -- run both createProviderSafeDeleteCrashPhase*LaunchScript tasks first"
[[ ! -e "$root" && ! -L "$root" ]] || refuse "requires an absent non-symlink disposable root -- never reuse a retained profile"
[[ ! -e "$receipts" ]] || refuse "requires absent prior receipts"
mkdir -- "$root" || refuse "cannot claim fresh disposable root"
mkdir -- "$receipts" || refuse "cannot claim receipts root"

printf '%s\n' 'eula=true' > "$root/eula.txt" || refuse "cannot write eula acknowledgement"
printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'level-name=world' > "$server_properties" || refuse "cannot write loopback server properties"

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
if (( ! marker_seen )); then
  kill -9 "$phase_a_pid" 2>/dev/null || true
  wait "$phase_a_pid" 2>/dev/null || true
  refuse "phase A never published its marker within ${marker_timeout_seconds}s"
fi

reported_pid=$(sed -n 's/^pid=//p' "$marker" | tr -d '\r')
[[ "$reported_pid" =~ ^[0-9]+$ ]] || refuse "marker reports no valid PID"
[[ "$reported_pid" == "$phase_a_pid" ]] || refuse "marker PID $reported_pid does not match the exact JVM this wrapper spawned ($phase_a_pid) -- refusing to kill anything"
[[ "$(sed -n 's/^durablePendingVerified=//p' "$marker" | tr -d '\r')" == "true" ]] || refuse "marker does not confirm the durable PENDING intent was verified before publication"

echo "verified marker PID $reported_pid matches spawned PID $phase_a_pid; sending SIGKILL" >&2
kill -9 "$phase_a_pid" || refuse "cannot signal phase A PID $phase_a_pid"

exited=0
for ((i = 0; i < exit_timeout_seconds; i++)); do
  if ! kill -0 "$phase_a_pid" 2>/dev/null; then exited=1; break; fi
  sleep 1
done
(( exited )) || refuse "phase A PID $phase_a_pid did not exit within ${exit_timeout_seconds}s of SIGKILL"
wait "$phase_a_pid" 2>/dev/null; phase_a_exit=$?
# Expected and recorded separately from a successful exit status: this PID
# was forcibly killed, so its exit status is not evidence of success or
# failure, only confirmation that it actually terminated.
printf '%s\n' "$phase_a_exit" > "$receipts/phase-a.forced-kill-exit"

if command -v lsof >/dev/null 2>&1; then
  lsof -- "$world_root/session.lock" > "$receipts/phase-a.session-lock-check.log" 2>&1
  [[ ! -s "$receipts/phase-a.session-lock-check.log" ]] || refuse "a process still holds the world session.lock after phase A's forced exit"
fi

### Phase B: an independent launch against the same disposable root.
/bin/bash -e -- "$generated_b" > "$receipts/phase-b.full.log" 2>&1
phase_b_exit=$?
printf '%s\n' "$phase_b_exit" > "$receipts/phase-b.exit"
(( phase_b_exit == 0 )) || refuse "phase B did not exit cleanly (exit $phase_b_exit) -- see $receipts/phase-b.full.log"

[[ -f "$result" ]] || refuse "phase B produced no result receipt"
grep -q '^targetQuarantined=true$' "$result" || refuse "phase B result does not confirm the target was quarantined"
grep -q '^targetReadableNow=false$' "$result" || refuse "phase B result does not confirm the target was unreadable"
grep -q '^unrelatedReady=true$' "$result" || refuse "phase B result does not confirm the unrelated verse replayed"
grep -q '^targetStorageIntact=true$' "$result" || refuse "phase B result does not confirm the target's on-disk storage survived"

echo "phase A forced-kill exit: $phase_a_exit (expected/recorded separately, not a success signal)" >&2
echo "phase B clean exit: $phase_b_exit" >&2
echo "all phase B result assertions confirmed" >&2
