#!/usr/bin/env bash
# Metadata-only checks for the transparent Phase-A/B guard.  It never starts
# Minecraft and never touches the preserved Phase-A root or receipts.
set -euo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
source "$workspace/scripts/providerSeededRestartReceiptLib.sh"

record=$(mktemp)
empty_duplicate=$(mktemp)
metadata=$(mktemp -d)
trap 'rm -f -- "$record" "$empty_duplicate"; rm -rf -- "$metadata"' EXIT
printf '%s\n' \
  'kind=offline-post-run-verification' \
  'scope=future-wrapper-properties-parser-only' \
  'phase_a_log_sha256=d761dae87cc6f5047b29dc03fc097703e377c81ab39bc3d90e7dd4e4a31943a9' \
  'phase_a_log_bytes=21799' \
  'phase_a_generated_exit=0' \
  'phase_a_tee_exit=0' \
  'phase_a_wrapper_exit=1' \
  'retained_state_sha256=d5cb872eec17c56ef8cbbe417c3dbab30c6ebcd87f333f2912bc33f2e26a4e07' \
  'decoded_key=ready.dynamicverse:native_seeded_restart' \
  'decoded_value=NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES' \
  'manifest_created=false' \
  'owner_release_created=false' \
  'phase_b_launched=false' > "$record"
[[ "$(seed_restart_verification_value "$record" decoded_key)" == ready.dynamicverse:native_seeded_restart ]]
if seed_restart_verification_value "$record" absent_required_key >/dev/null; then
  echo 'missing verification key unexpectedly accepted' >&2
  exit 1
fi
printf 'decoded_key=shadow\n' >> "$record"
if seed_restart_verification_value "$record" decoded_key >/dev/null; then
  echo 'duplicate verification key unexpectedly accepted' >&2
  exit 1
fi
printf 'decoded_key=\n' > "$empty_duplicate"
if seed_restart_verification_value "$empty_duplicate" decoded_key >/dev/null; then
  echo 'empty required verification value unexpectedly accepted' >&2
  exit 1
fi

# Exercise the same production-used transparent guard with metadata only.
# No Minecraft root, Java process, or receipt directory is involved.
root="$metadata/root"; world="$root/world"; phase_a="$metadata/original-a"; state="$world/state.properties"; postrun="$metadata/postrun"
mkdir -p "$world/dimensions/dynamicverse/native_seeded_restart/region" "$phase_a"
printf '0\n' > "$phase_a/generated.exit"; printf '0\n' > "$phase_a/tee.exit"; printf '1\n' > "$phase_a/wrapper.exit"
printf '%s\n' '========= 1 GAME TESTS COMPLETE' 'All 1 required tests passed :)' 'Game test server shutting down' > "$phase_a/full.log"
printf '%s\n' 'ready.dynamicverse\:native_seeded_restart=NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES' > "$state"
printf 'sentinel-witness\n' > "$world/dimensions/dynamicverse/native_seeded_restart/region/r.0.0.mca"
log_hash=$(seed_restart_file_hash "$phase_a/full.log"); log_hash=${log_hash%% *}; log_bytes=$(wc -c < "$phase_a/full.log")
state_hash=$(seed_restart_file_hash "$state"); state_hash=${state_hash%% *}
printf '%s\n' \
  'kind=offline-post-run-verification' \
  'scope=future-wrapper-properties-parser-only' \
  "phase_a_log_sha256=$log_hash" \
  "phase_a_log_bytes=$log_bytes" \
  'phase_a_generated_exit=0' 'phase_a_tee_exit=0' 'phase_a_wrapper_exit=1' \
  "retained_state_sha256=$state_hash" \
  'decoded_key=ready.dynamicverse:native_seeded_restart' \
  'decoded_value=NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES' \
  'manifest_created=false' 'owner_release_created=false' 'phase_b_launched=false' > "$postrun"
seed_restart_require_transparent_phase_a_with_expected "$phase_a" "$postrun" "$root" "$world" "$state" "$log_hash" "$log_bytes" "$state_hash" 'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES'
printf '2\n' > "$phase_a/wrapper.exit"
if seed_restart_require_transparent_phase_a_with_expected "$phase_a" "$postrun" "$root" "$world" "$state" "$log_hash" "$log_bytes" "$state_hash" 'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES'; then
  echo 'wrong original receipt unexpectedly accepted' >&2; exit 1
fi
printf '1\n' > "$phase_a/wrapper.exit"
printf 'altered\n' >> "$phase_a/full.log"
if seed_restart_require_transparent_phase_a_with_expected "$phase_a" "$postrun" "$root" "$world" "$state" "$log_hash" "$log_bytes" "$state_hash" 'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES'; then
  echo 'wrong original log unexpectedly accepted' >&2; exit 1
fi
sed -i '$d' "$phase_a/full.log"
printf 'altered\n' >> "$state"
if seed_restart_require_transparent_phase_a_with_expected "$phase_a" "$postrun" "$root" "$world" "$state" "$log_hash" "$log_bytes" "$state_hash" 'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES'; then
  echo 'wrong retained state unexpectedly accepted' >&2; exit 1
fi
sed -i '$d' "$state"
printf 'provider-a\n' > "$metadata/provider"; provider_hash=$(seed_restart_file_hash "$metadata/provider"); provider_hash=${provider_hash%% *}
seed_restart_require_snapshot_file provider "$metadata/provider" "$provider_hash"
printf 'provider-b\n' > "$metadata/provider"
if seed_restart_require_snapshot_file provider "$metadata/provider" "$provider_hash"; then echo 'wrong provider unexpectedly accepted' >&2; exit 1; fi
printf 'raw-a\n' > "$metadata/raw"; raw_hash=$(seed_restart_file_hash "$metadata/raw"); raw_hash=${raw_hash%% *}
seed_restart_require_snapshot_file raw "$metadata/raw" "$raw_hash"
printf 'raw-b\n' > "$metadata/raw"
if seed_restart_require_snapshot_file raw "$metadata/raw" "$raw_hash"; then echo 'wrong raw input unexpectedly accepted' >&2; exit 1; fi
printf '%s\n' "$metadata/provider" > "$metadata/closure.args"
closure_hash=$(seed_restart_closure_hash "$metadata/closure.args")
printf 'provider-c\n' > "$metadata/provider"
if [[ "$(seed_restart_closure_hash "$metadata/closure.args")" == "$closure_hash" ]]; then echo 'wrong closure unexpectedly accepted' >&2; exit 1; fi

world_hash=$(seed_restart_tree_hash "$world"); region="$world/dimensions/dynamicverse/native_seeded_restart/region/r.0.0.mca"; region_hash=$(seed_restart_file_hash "$region"); region_hash=${region_hash%% *}
evidence="$metadata/world-evidence"
printf '%s\n' \
  'kind=transparent-post-run-world-evidence' "game_root=$root" "world_root=$world" "state_path=$state" 'dimension_key=dynamicverse:native_seeded_restart' \
  "original_phase_a_log_sha256=$log_hash" "world_tree_sha256=$world_hash" \
  'sentinel_region_relative=dimensions/dynamicverse/native_seeded_restart/region/r.0.0.mca' "sentinel_region_path=$region" "sentinel_region_sha256=$region_hash" 'sentinel_claim=post-load-verification-required' > "$evidence"
seed_restart_require_postrun_world_evidence "$evidence" "$root" "$world" "$state" "$log_hash"
printf 'altered\n' >> "$region"
if seed_restart_require_postrun_world_evidence "$evidence" "$root" "$world" "$state" "$log_hash"; then echo 'altered world evidence unexpectedly accepted' >&2; exit 1; fi
sed -i '$d' "$region"
rm -- "$region"
if seed_restart_require_postrun_world_evidence "$evidence" "$root" "$world" "$state" "$log_hash"; then echo 'missing sentinel witness unexpectedly accepted' >&2; exit 1; fi
printf 'sentinel-witness\n' > "$region"
host="$metadata/host-release"
printf '%s\n' \
  "phase_a_log_sha256=$log_hash" "phase_a_log_bytes=$log_bytes" 'phase_a_generated_exit=0' 'phase_a_tee_exit=0' 'phase_a_wrapper_exit=1' \
  "game_root=$root" "world_root=$world" "state_sha256=$state_hash" 'observer=tester' 'observed_at=offline' \
  'host_process_observation=none' 'host_cwd_observation=none' 'java_file_lock_observation=none' 'released=1' > "$host"
seed_restart_require_host_release "$host" "$log_hash" "$log_bytes" "$root" "$world" "$state_hash"
printf 'released=0\n' >> "$host"
if seed_restart_require_host_release "$host" "$log_hash" "$log_bytes" "$root" "$world" "$state_hash"; then echo 'wrong host provenance unexpectedly accepted' >&2; exit 1; fi

printf 'offline-transparent-guard=pass missing-key=refused duplicate-key=refused empty-value=refused original-receipt=refused log=refused state=refused provider=refused raw-input=refused closure=refused world-evidence=refused host=refused\n'
