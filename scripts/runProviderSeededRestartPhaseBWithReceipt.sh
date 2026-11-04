#!/usr/bin/env bash
# Future execution only. Requires the exact successful phase-A manifest and retained world.
set -uo pipefail

workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
source "$workspace/scripts/providerSeededRestartReceiptLib.sh" || exit 125
game_root="$workspace/run/providerSeededRestart-native-explicit-seed"; world_root="$game_root/world"
generated="$workspace/build/moddev/runProviderSeededRestartPhaseBGameTest.sh"
normal="$workspace/build/moddev/providerSeededRestartPhaseBGameTestRunClasspath.txt"
legacy="$workspace/build/moddev/providerSeededRestartPhaseBGameTestLegacyClasspath.txt"
vm="$workspace/build/moddev/providerSeededRestartPhaseBGameTestRunVmArgs.txt"
program="$workspace/build/moddev/providerSeededRestartPhaseBGameTestRunProgramArgs.txt"
log4j="$workspace/build/moddev/providerSeededRestartPhaseBGameTestLog4j2.xml"
phase_a="$workspace/build/moddev/providerSeededRestartPhaseA-receipts"
# Immutable, separately retained post-run parser verification.  It records
# that the original Phase-A wrapper failed its old unescaped-colon predicate;
# it is not an original-success manifest and cannot turn wrapper=1 into 0.
postrun=/tmp/m470-phaseA-postrun-verification-51115.txt
# This future independent record binds preserved world bytes to the original
# run.  It is intentionally absent today and this wrapper never creates it.
world_evidence=/tmp/m470-phaseA-postrun-world-evidence.txt
# Must be written independently by the host-side coordinator immediately
# before a separately assigned Phase-B launch.  This wrapper never creates it.
host_release="$workspace/build/moddev/providerSeededRestartPhaseA.host-release"
receipts="$workspace/build/moddev/providerSeededRestartPhaseB-receipts"; b_manifest="$workspace/build/moddev/providerSeededRestartPhaseB.manifest"; state="$world_root/data/dynamicverse-managed-state.properties"
phase=B
fixture=reloadpersistedseededreadyafterfreshjvm
fixture_class="$workspace/build/classes/java/main/io/github/brooswitminecraft/dynamicverse/lifecycle/ProviderSeededRestartPhaseBGameTest.class"
claimed=0; final=125
finish() { if (( claimed )) && ! printf '%s\n' "$final" > "$receipts/wrapper.exit"; then trap - EXIT; exit 125; fi; }
trap finish EXIT
refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }
postrun_value() { seed_restart_verification_value "$postrun" "$1"; }
host_value() { seed_restart_verification_value "$host_release" "$1"; }
receipt_value() { [[ -f "$1" && ! -L "$1" ]] || return 1; cat -- "$1"; }
hash() { seed_restart_file_hash "$1" | awk '{print $1}'; }
tree_hash() { seed_restart_tree_hash "$1"; }
closure_hash() { seed_restart_closure_hash "$1"; }
modfolder_hash() { seed_restart_modfolder_hash "$1"; }
need_hash() { local v; if ! v=$("$2" "$3"); then refuse "cannot hash $1"; fi; [[ -n "$v" ]] || refuse "empty hash for $1"; printf '%s' "$v"; }
normalized_hash() { sed -E 's/providerSeededRestartPhase[AB]GameTest/providerSeededRestartPhase<GameTest>/g; s/dynamicverse\.providerSeededRestartPhase=[AB]/dynamicverse.providerSeededRestartPhase=<phase>/g' "$1" | sha256sum | awk '{print $1}'; }
expect_hash() { [[ "$(need_hash "$1" hash "$2")" == "$3" ]] || refuse "$1 identity differs from retained candidate snapshot"; }
write_phase_b_manifest() {
  printf 'game_root=%s\nworld_root=%s\nphase=%s\nfixture=%s\nnamespace=dynamicverse\nstate_sha256=%s\nprovider_path=%s\nprovider_sha256=%s\nfull_log_sha256=%s\nfull_log_bytes=%s\ngenerated_exit_sha256=%s\ntee_exit_sha256=%s\n' "$game_root" "$world_root" "$phase" "$fixture" "$state_hash" "$provider" "$provider_hash" "$full_log_hash" "$full_log_bytes" "$generated_exit_hash" "$tee_exit_hash" || return 1
  printf 'generated_sha256=%s\nnormal_sha256=%s\nlegacy_sha256=%s\nvm_sha256=%s\nprogram_sha256=%s\nlog4j_sha256=%s\nnormal_closure_sha256=%s\nlegacy_closure_sha256=%s\nmodfolders_sha256=%s\nmain_tree_sha256=%s\nresource_tree_sha256=%s\n' "$generated_hash" "$normal_hash" "$legacy_hash" "$vm_hash" "$program_hash" "$log4j_hash" "$normal_closure" "$legacy_closure" "$modfolders" "$main_tree" "$resource_tree" || return 1
  printf 'selector_manifest_begin\n%s\nselector_manifest_end\n' "$selector_manifest" || return 1
  printf 'normal_closure_manifest_begin\n' || return 1; seed_restart_closure_manifest "$normal" || return 1; printf 'normal_closure_manifest_end\nlegacy_closure_manifest_begin\n' || return 1; seed_restart_closure_manifest "$legacy" || return 1; printf 'legacy_closure_manifest_end\nmodfolder_manifest_begin\n' || return 1; seed_restart_modfolder_manifest "$generated" || return 1; printf 'modfolder_manifest_end\n' || return 1
  printf 'generated_raw_begin\n' || return 1; cat -- "$generated" || return 1; printf '\ngenerated_raw_end\nvm_raw_begin\n' || return 1; cat -- "$vm" || return 1; printf '\nvm_raw_end\nprogram_raw_begin\n' || return 1; cat -- "$program" || return 1; printf '\nprogram_raw_end\nlog4j_raw_begin\n' || return 1; cat -- "$log4j" || return 1; printf '\nlog4j_raw_end\n' || return 1
}

[[ -f "$generated" && -f "$normal" && -f "$legacy" && -f "$vm" && -f "$program" && -f "$log4j" ]] || refuse "generated inputs missing"
seed_restart_require_generated_inputs "$generated" "$normal" "$legacy" "$vm" "$program" "$log4j" "$game_root" || refuse "phase-B generated input binding is invalid"
selector_manifest=$(seed_restart_require_selector "$vm" "$phase" "$fixture" "$fixture_class") || refuse "phase-B selector or fixture identity is invalid"
[[ -d "$game_root" && ! -L "$game_root" && "$(readlink -f -- "$game_root")" == "$game_root" ]] || refuse "retained game root invalid"
root_entries=$(find "$game_root" -mindepth 1 -print -quit) || refuse "cannot enumerate retained game root"
[[ -n "$root_entries" ]] || refuse "retained game root is empty"
[[ -d "$world_root" && ! -L "$world_root" && -f "$state" && ! -L "$state" ]] || refuse "retained world/state invalid"
[[ -d "$phase_a" && -f "$postrun" && ! -L "$postrun" && ! -e "$receipts" && ! -e "$b_manifest" ]] || refuse "original Phase-A/post-run evidence or fresh B receipts invalid"
# The retained original run is intentionally transparent: it functionally
# passed the one fixture but wrapper postcondition evaluation failed.  Require
# 0/0/1 exactly, never normalize it to a fabricated successful manifest.
[[ "$(receipt_value "$phase_a/generated.exit")" == 0 && "$(receipt_value "$phase_a/tee.exit")" == 0 && "$(receipt_value "$phase_a/wrapper.exit")" == 1 ]] || refuse "original Phase-A receipt tuple is not generated=0/tee=0/wrapper=1"
seed_restart_require_terminal_log "$phase_a/full.log" createseedednormalpersistandclose || refuse "phase-A expected fixture/pass/shutdown evidence is invalid"
 a_log_hash=$(need_hash phase-a-log hash "$phase_a/full.log") || refuse "cannot hash phase-A log"; a_log_bytes=$(wc -c < "$phase_a/full.log") || refuse "cannot count phase-A log"
[[ "$a_log_hash" == d761dae87cc6f5047b29dc03fc097703e377c81ab39bc3d90e7dd4e4a31943a9 && "$a_log_bytes" == 21799 ]] || refuse "original Phase-A log identity differs"
[[ "$(postrun_value kind)" == offline-post-run-verification && "$(postrun_value scope)" == future-wrapper-properties-parser-only && "$(postrun_value phase_a_log_sha256)" == "$a_log_hash" && "$(postrun_value phase_a_log_bytes)" == "$a_log_bytes" && "$(postrun_value phase_a_generated_exit)" == 0 && "$(postrun_value phase_a_tee_exit)" == 0 && "$(postrun_value phase_a_wrapper_exit)" == 1 && "$(postrun_value manifest_created)" == false && "$(postrun_value owner_release_created)" == false && "$(postrun_value phase_b_launched)" == false ]] || refuse "post-run verification does not bind the original failed-wrapper Phase-A record"
state_hash=$(need_hash state hash "$state") || refuse "cannot hash retained state"; [[ "$state_hash" == d5cb872eec17c56ef8cbbe417c3dbab30c6ebcd87f333f2912bc33f2e26a4e07 && "$state_hash" == "$(postrun_value retained_state_sha256)" ]] || refuse "retained state changed"
seed_restart_require_postrun_world_evidence "$world_evidence" "$game_root" "$world_root" "$state" "$a_log_hash" || refuse "missing or mismatched post-run world evidence"
normal_closure=$(need_hash normal-closure closure_hash "$normal") || refuse "cannot hash normal closure"; legacy_closure=$(need_hash legacy-closure closure_hash "$legacy") || refuse "cannot hash legacy closure"; modfolders=$(need_hash mod-folders modfolder_hash "$generated") || refuse "cannot hash FML mod folders"; [[ "$normal_closure" == 361c8258c620fe8ed871c836eedfbaf0873967879f5fe38b4a6c644f0694c341 && "$legacy_closure" == d3ed5e4c987fa7666afc39735213fa7ab1ecf79c40e0ef61a5c47bddae427bb0 && "$modfolders" == 93bab7035c8bdf454008200a5a48a482eb613fc1dfbb2ecdcaf0ecfc18e96e38 ]] || refuse "launch dependency or FML mod-folder closure changed"
main_tree=$(need_hash main-classes tree_hash "$workspace/build/classes/java/main") || refuse "cannot hash Verse classes"; resource_tree=$(need_hash main-resources tree_hash "$workspace/build/resources/main") || refuse "cannot hash Verse resources"; test_tree=$(need_hash test-classes tree_hash "$workspace/build/classes/java/test") || refuse "cannot hash Verse test classes"; [[ "$main_tree" == 419667293ef5b940ce467892faf29f658280ebecb44c5656008ee6a971daccaf && "$resource_tree" == 906bbdb39f87e3dc58021057aa1f7eeeda28fd62c4bb7572c2c74f7d74c7cc9e && "$test_tree" == 4d31bd0d449b03bd0133abed5740bfd95b9d6a740de42cfdd15f020d1d41114e ]] || refuse "Verse classes/resources changed"
provider=$(seed_restart_provider_input "$normal") || refuse "cannot resolve exactly one provider input"
provider_hash=$(need_hash provider hash "$provider") || refuse "cannot hash provider"; [[ "$provider" == /tmp/dynamicdimensions-composite/neoforge/build/libs/dynamicdimensions-neoforge-0.10.0+4b09389e-dirty.candidate-51101.jar && "$provider_hash" == 50b7b75a0751f9774a0586926a9e441512fb62fc815f992052aed9647fc3b30e && "$(postrun_value candidate_path)" == "$provider" && "$(postrun_value candidate_sha256)" == "$provider_hash" ]] || refuse "provider input changed"
generated_hash=$(need_hash generated hash "$generated") || refuse "cannot hash generated"; normal_hash=$(need_hash normal hash "$normal") || refuse "cannot hash normal"; legacy_hash=$(need_hash legacy hash "$legacy") || refuse "cannot hash legacy"; vm_hash=$(need_hash vm hash "$vm") || refuse "cannot hash VM"; program_hash=$(need_hash program hash "$program") || refuse "cannot hash program"; log4j_hash=$(need_hash log4j hash "$log4j") || refuse "cannot hash log4j config"
generated_normalized=$(normalized_hash "$generated") || refuse "cannot normalize generated script"; vm_normalized=$(normalized_hash "$vm") || refuse "cannot normalize VM inputs"; program_normalized=$(normalized_hash "$program") || refuse "cannot normalize program inputs"
[[ "$generated_hash" == 582952c16a6957f262e4f23be91b4c01ff6626d4209b01827f560c9ce92962e1 && "$normal_hash" == ca1f044d50103afafee24baea35ed4dc8c8741608417c4f6f5d1f8c9b19fbcc7 && "$legacy_hash" == 94e526522425cd0f846d2673dce959f16727d636df41e0b9ac6d86143b331f8c && "$vm_hash" == 2a27edb8a944f291c57507ee6589c4792b28cb50bcea2e5dcbe33645e9215ca2 && "$program_hash" == 6a8a78e5f3363a13ac2794f1c98c92ee730d2517979a0884fc707048359064d7 && "$log4j_hash" == c030dc5b9a8973363bc89d49ba493f5f48b770a21bfc67a53d6ec5c98a2bc1a6 && "$generated_normalized" == 3f234e0d4fb381ad92bfd19e345b45198570c42b69f47ff04167042329384bbe && "$vm_normalized" == afeca6533871c9270e1558ef3524c5a7c26476783652c63b0e73d562d40b88e0 && "$program_normalized" == 6a8a78e5f3363a13ac2794f1c98c92ee730d2517979a0884fc707048359064d7 ]] || refuse "B inputs differ beyond reviewed selector changes"
# Retain the actual candidate pre-run A identities too.  They are a fixed
# 51106 snapshot, not a post-hoc replacement manifest.
seed_restart_require_snapshot_file phase-a-script "$workspace/build/moddev/runProviderSeededRestartPhaseAGameTest.sh" 5c8bf95e5ebd7486555164d1953d2898e014b303b10eaa11054d750cb7cf66a1 || refuse "phase-A script differs from retained candidate snapshot"
seed_restart_require_snapshot_file phase-a-normal "$workspace/build/moddev/providerSeededRestartPhaseAGameTestRunClasspath.txt" ca1f044d50103afafee24baea35ed4dc8c8741608417c4f6f5d1f8c9b19fbcc7 || refuse "phase-A normal inputs differ from retained candidate snapshot"
seed_restart_require_snapshot_file phase-a-legacy "$workspace/build/moddev/providerSeededRestartPhaseAGameTestLegacyClasspath.txt" 94e526522425cd0f846d2673dce959f16727d636df41e0b9ac6d86143b331f8c || refuse "phase-A legacy inputs differ from retained candidate snapshot"
seed_restart_require_snapshot_file phase-a-vm "$workspace/build/moddev/providerSeededRestartPhaseAGameTestRunVmArgs.txt" 1c4ac57ce87f40a9a2d9daa01de80d68cc0a640f8179eb945551f2177a2c343a || refuse "phase-A VM inputs differ from retained candidate snapshot"
seed_restart_require_snapshot_file phase-a-program "$workspace/build/moddev/providerSeededRestartPhaseAGameTestRunProgramArgs.txt" 6a8a78e5f3363a13ac2794f1c98c92ee730d2517979a0884fc707048359064d7 || refuse "phase-A program inputs differ from retained candidate snapshot"
seed_restart_require_snapshot_file phase-a-log4j "$workspace/build/moddev/providerSeededRestartPhaseAGameTestLog4j2.xml" c030dc5b9a8973363bc89d49ba493f5f48b770a21bfc67a53d6ec5c98a2bc1a6 || refuse "phase-A log4j inputs differ from retained candidate snapshot"
# A future independent host observation must bind this exact original tuple;
# its absence is a refusal, not a wrapper-created owner release.
seed_restart_require_host_release "$host_release" "$a_log_hash" "$a_log_bytes" "$game_root" "$world_root" "$state_hash" || refuse "missing or mismatched independent host process/cwd/JavaFileLock release evidence"
seed_restart_require_transparent_phase_a "$phase_a" "$postrun" "$game_root" "$world_root" "$state" || refuse "transparent original/post-run Phase-A guard failed"
state_record=$(seed_restart_properties_value "$state" 'ready.dynamicverse:native_seeded_restart') || refuse "retained READY state key is missing or malformed"
[[ "$state_record" == 'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES' && "$(postrun_value decoded_key)" == ready.dynamicverse:native_seeded_restart && "$(postrun_value decoded_value)" == "$state_record" ]] || refuse "retained state provenance changed"
! grep -q '^operation\.' "$state" || refuse "retained state contains a nonterminal operation"
mkdir "$receipts" || refuse "cannot claim phase-B receipts"; claimed=1
cd -- "$game_root" || refuse "cannot enter retained game root"
set +e
/bin/bash -e -- "$generated" 2>&1 | tee "$receipts/full.log"
status=("${PIPESTATUS[@]}")
set -e
generated_status=${status[0]:-125}; tee_status=${status[1]:-125}
printf '%s\n' "$generated_status" > "$receipts/generated.exit" || exit 125
printf '%s\n' "$tee_status" > "$receipts/tee.exit" || exit 125
(( generated_status == 0 && tee_status == 0 )) && final=0 || final=1
if (( final == 0 )); then
    seed_restart_require_terminal_log "$receipts/full.log" "$fixture" || final=1
fi
if (( final == 0 )); then
    full_log_hash=$(need_hash full-log hash "$receipts/full.log") || final=125
    full_log_bytes=$(wc -c < "$receipts/full.log") || final=125
    [[ "$full_log_bytes" =~ ^[1-9][0-9]*$ ]] || final=1
    generated_exit_hash=$(need_hash generated-exit hash "$receipts/generated.exit") || final=125
    tee_exit_hash=$(need_hash tee-exit hash "$receipts/tee.exit") || final=125
    if (( final == 0 )) && ! write_phase_b_manifest > "$b_manifest"; then
        final=125
    fi
fi
exit "$final"
