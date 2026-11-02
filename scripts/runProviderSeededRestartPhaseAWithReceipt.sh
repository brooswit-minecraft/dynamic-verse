#!/usr/bin/env bash
# Future execution only. This wrapper never creates, resets, or deletes the root.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
source "$workspace/scripts/providerSeededRestartReceiptLib.sh" || exit 125
game_root="$workspace/run/providerSeededRestart-native-explicit-seed"
world_root="$game_root/world" # Pinned GameTestServer layout: getWorldPath(ROOT) is gameDirectory/world.
generated="$workspace/build/moddev/runProviderSeededRestartPhaseAGameTest.sh"
normal="$workspace/build/moddev/providerSeededRestartPhaseAGameTestRunClasspath.txt"
legacy="$workspace/build/moddev/providerSeededRestartPhaseAGameTestLegacyClasspath.txt"
vm="$workspace/build/moddev/providerSeededRestartPhaseAGameTestRunVmArgs.txt"
program="$workspace/build/moddev/providerSeededRestartPhaseAGameTestRunProgramArgs.txt"
log4j="$workspace/build/moddev/providerSeededRestartPhaseAGameTestLog4j2.xml"
receipts="$workspace/build/moddev/providerSeededRestartPhaseA-receipts"
manifest="$workspace/build/moddev/providerSeededRestartPhaseA.manifest"
state="$world_root/data/dynamicverse-managed-state.properties"
phase=A
fixture=createseedednormalpersistandclose
fixture_class="$workspace/build/classes/java/main/io/github/brooswitminecraft/dynamicverse/lifecycle/ProviderSeededRestartPhaseAGameTest.class"
claimed=0; final=125
finish() { if (( claimed )) && ! printf '%s\n' "$final" > "$receipts/wrapper.exit"; then trap - EXIT; exit 125; fi; }
trap finish EXIT
refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }
hash() { seed_restart_file_hash "$1" | awk '{print $1}'; }
tree_hash() { seed_restart_tree_hash "$1"; }
closure_hash() { seed_restart_closure_hash "$1"; }
modfolder_hash() { seed_restart_modfolder_hash "$1"; }
need_hash() { local v; if ! v=$("$2" "$3"); then refuse "cannot hash $1"; fi; [[ -n "$v" ]] || refuse "empty hash for $1"; printf '%s' "$v"; }
normalized_hash() { sed -E 's/providerSeededRestartPhase[AB]GameTest/providerSeededRestartPhase<GameTest>/g; s/dynamicverse\.providerSeededRestartPhase=[AB]/dynamicverse.providerSeededRestartPhase=<phase>/g' "$1" | sha256sum | awk '{print $1}'; }
write_phase_a_manifest() {
  printf 'game_root=%s\nworld_root=%s\nkey=dynamicverse:native_seeded_restart\nseed=9123417733\nphase=%s\nfixture=%s\nnamespace=dynamicverse\n' "$game_root" "$world_root" "$phase" "$fixture" || return 1
  printf 'state_sha256=%s\nprovider_path=%s\nprovider_sha256=%s\nfull_log_sha256=%s\nfull_log_bytes=%s\ngenerated_exit_sha256=%s\ntee_exit_sha256=%s\n' "$state_hash" "$provider" "$provider_hash" "$full_log_hash" "$full_log_bytes" "$generated_exit_hash" "$tee_exit_hash" || return 1
  printf 'generated_sha256=%s\ngenerated_normalized_sha256=%s\nnormal_sha256=%s\nlegacy_sha256=%s\nvm_sha256=%s\nvm_normalized_sha256=%s\nprogram_sha256=%s\nprogram_normalized_sha256=%s\nlog4j_sha256=%s\nnormal_closure_sha256=%s\nlegacy_closure_sha256=%s\nmodfolders_sha256=%s\nmain_tree_sha256=%s\nresource_tree_sha256=%s\nstate_ready_record=%s\n' "$generated_hash" "$generated_normalized" "$normal_hash" "$legacy_hash" "$vm_hash" "$vm_normalized" "$program_hash" "$program_normalized" "$log4j_hash" "$closure" "$legacy_closure" "$modfolders" "$main_tree" "$resource_tree" "$state_record" || return 1
  printf 'selector_manifest_begin\n%s\nselector_manifest_end\n' "$selector_manifest" || return 1
  printf 'normal_closure_manifest_begin\n' || return 1; seed_restart_closure_manifest "$normal" || return 1; printf 'normal_closure_manifest_end\nlegacy_closure_manifest_begin\n' || return 1; seed_restart_closure_manifest "$legacy" || return 1; printf 'legacy_closure_manifest_end\nmodfolder_manifest_begin\n' || return 1; seed_restart_modfolder_manifest "$generated" || return 1; printf 'modfolder_manifest_end\n' || return 1
  printf 'generated_raw_begin\n' || return 1; cat -- "$generated" || return 1; printf '\ngenerated_raw_end\nvm_raw_begin\n' || return 1; cat -- "$vm" || return 1; printf '\nvm_raw_end\nprogram_raw_begin\n' || return 1; cat -- "$program" || return 1; printf '\nprogram_raw_end\nlog4j_raw_begin\n' || return 1; cat -- "$log4j" || return 1; printf '\nlog4j_raw_end\n' || return 1
  printf 'phase_a_generated_exit=0\nphase_a_tee_exit=0\nphase_a_shutdown=1\n' || return 1
}

[[ -f "$generated" && -f "$normal" && -f "$legacy" && -f "$vm" && -f "$program" && -f "$log4j" ]] || refuse "generated inputs missing"
seed_restart_require_generated_inputs "$generated" "$normal" "$legacy" "$vm" "$program" "$log4j" "$game_root" || refuse "phase-A generated input binding is invalid"
selector_manifest=$(seed_restart_require_selector "$vm" "$phase" "$fixture" "$fixture_class") || refuse "phase-A selector or fixture identity is invalid"
[[ -d "$game_root" && ! -L "$game_root" && "$(readlink -f -- "$game_root")" == "$game_root" ]] || refuse "game root invalid"
root_entries=$(find "$game_root" -mindepth 1 -print -quit) || refuse "cannot enumerate phase-A game root"
[[ -z "$root_entries" ]] || refuse "phase-A game root is not empty"
[[ ! -e "$receipts" && ! -e "$manifest" ]] || refuse "phase-A evidence already exists"
mkdir "$receipts" || refuse "cannot claim phase-A receipts"; claimed=1
generated_hash=$(need_hash generated hash "$generated") || refuse "cannot hash generated"
normal_hash=$(need_hash normal hash "$normal") || refuse "cannot hash normal"
legacy_hash=$(need_hash legacy hash "$legacy") || refuse "cannot hash legacy"
vm_hash=$(need_hash vm hash "$vm") || refuse "cannot hash VM inputs"
program_hash=$(need_hash program hash "$program") || refuse "cannot hash program inputs"
log4j_hash=$(need_hash log4j hash "$log4j") || refuse "cannot hash log4j config"
closure=$(need_hash normal-closure closure_hash "$normal") || refuse "cannot hash normal closure"
legacy_closure=$(need_hash legacy-closure closure_hash "$legacy") || refuse "cannot hash legacy closure"
modfolders=$(need_hash mod-folders modfolder_hash "$generated") || refuse "cannot hash FML mod folders"
main_tree=$(need_hash main-classes tree_hash "$workspace/build/classes/java/main") || refuse "cannot hash Verse classes"
resource_tree=$(need_hash main-resources tree_hash "$workspace/build/resources/main") || refuse "cannot hash Verse resources"
cd -- "$game_root" || refuse "cannot enter phase-A game root"
set +e
/bin/bash -e -- "$generated" 2>&1 | tee "$receipts/full.log"
status=("${PIPESTATUS[@]}")
set -e
generated_status=${status[0]}; tee_status=${status[1]}
printf '%s\n' "$generated_status" > "$receipts/generated.exit" || exit 125
printf '%s\n' "$tee_status" > "$receipts/tee.exit" || exit 125
(( generated_status == 0 && tee_status == 0 )) || { final=1; exit "$final"; }
[[ -d "$world_root" && ! -L "$world_root" && -f "$state" && ! -L "$state" ]] || { final=1; exit "$final"; }
seed_restart_require_terminal_log "$receipts/full.log" "$fixture" || { final=1; exit "$final"; }
    provider=$(seed_restart_provider_input "$normal") || { final=1; exit "$final"; }
provider_hash=$(need_hash provider hash "$provider") || refuse "cannot hash provider"
state_hash=$(need_hash state hash "$state") || refuse "cannot hash retained state"
full_log_hash=$(need_hash full-log hash "$receipts/full.log") || refuse "cannot hash full log"
full_log_bytes=$(wc -c < "$receipts/full.log") || refuse "cannot count full log"
[[ "$full_log_bytes" =~ ^[1-9][0-9]*$ ]] || { final=1; exit "$final"; }
generated_exit_hash=$(need_hash generated-exit hash "$receipts/generated.exit") || refuse "cannot hash generated exit receipt"
tee_exit_hash=$(need_hash tee-exit hash "$receipts/tee.exit") || refuse "cannot hash tee exit receipt"
state_record=$(seed_restart_properties_value "$state" 'ready.dynamicverse:native_seeded_restart') || { final=1; exit "$final"; }
[[ "$state_record" == 'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES' ]] || { final=1; exit "$final"; }
! grep -q '^operation\.' "$state" || { final=1; exit "$final"; }
generated_normalized=$(normalized_hash "$generated") || refuse "cannot normalize generated script"
vm_normalized=$(normalized_hash "$vm") || refuse "cannot normalize VM inputs"
program_normalized=$(normalized_hash "$program") || refuse "cannot normalize program inputs"
write_phase_a_manifest > "$manifest" || { final=125; exit "$final"; }
final=0
