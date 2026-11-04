#!/usr/bin/env bash
# Future-only ordinary O1/O2 runner.  Never reset/delete a root or receipt.
set -uo pipefail
phase=${1:-}
[[ "$phase" == O1 || "$phase" == O2 ]] || { echo 'usage: O1|O2' >&2; exit 125; }
workspace=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P) || exit 125
source "$workspace/scripts/providerOrdinaryRestartReceiptLib.sh" || exit 125
root="$workspace/run/providerOrdinaryRestart-o1o2-loopback-retry"
world_root="$root/world" # Minecraft Main defaults --universe . and server.properties level-name world.
suffix="providerOrdinaryRestartRetry${phase}"
generated="$workspace/build/moddev/runProviderOrdinaryRestartRetry${phase}.sh"
normal="$workspace/build/moddev/${suffix}RunClasspath.txt"
legacy="$workspace/build/moddev/${suffix}LegacyClasspath.txt"
vm="$workspace/build/moddev/${suffix}RunVmArgs.txt"
program="$workspace/build/moddev/${suffix}RunProgramArgs.txt"
log4j="$workspace/build/moddev/${suffix}Log4j2.xml"
receipts="$workspace/build/moddev/${suffix}-receipts"
manifest="$workspace/build/moddev/${suffix}.postshutdown"
o1_receipts="$workspace/build/moddev/providerOrdinaryRestartRetryO1-receipts"
o1_manifest="$workspace/build/moddev/providerOrdinaryRestartRetryO1.postshutdown"
host_release="$workspace/build/moddev/providerOrdinaryRestartRetryO1.host-release"
server_properties="$root/server.properties"
state="$world_root/data/dynamicverse-managed-state.properties"
o1_semantic="$world_root/data/dynamicverse-ordinary-restart-o1.properties"
o2_semantic="$world_root/data/dynamicverse-ordinary-restart-o2.properties"
main_classes="$workspace/build/classes/java/main"
main_resources="$workspace/build/resources/main"
test_classes="$workspace/build/classes/java/test"
test_resources="$workspace/build/resources/test"
claimed=0; final=125
finish() { if (( claimed )) && ! printf '%s\n' "$final" > "$receipts/wrapper.exit"; then trap - EXIT; exit 125; fi; }
trap finish EXIT
refuse() { ordinary_refuse "$@"; }
ordinary_require_generated "$generated" "$normal" "$legacy" "$vm" "$program" "$log4j" "$root" "$phase" "$main_classes" "$main_resources" "$test_classes" "$test_resources" || refuse "generated input/root binding mismatch"
if [[ "$phase" == O1 ]]; then
  [[ ! -e "$root" && ! -L "$root" ]] || refuse "O1 requires an absent non-symlink root"
  mkdir "$root" || refuse "cannot claim fresh O1 game directory"
  [[ -d "$root" && ! -L "$root" && "$(readlink -f -- "$root")" == "$root" ]] || refuse "O1 root invalid after claim"
  ordinary_directory_is_empty "$root" || refuse "O1 requires canonical empty root"
  [[ ! -e "$receipts" && ! -e "$manifest" ]] || refuse "O1 requires absent outputs"
  # These are the only O1 prelaunch files, written after the empty-root proof
  # and before the generated script enters this game directory.  Native
  # transport remains the pinned default; loopback plus port zero avoids a
  # fixed external listener without changing server lifecycle semantics.
  printf '%s\n' 'eula=true' > "$root/eula.txt" || refuse "cannot write O1 eula acknowledgement"
  printf '%s\n' 'server-ip=127.0.0.1' 'server-port=0' 'level-name=world' > "$server_properties" || refuse "cannot write O1 loopback server properties"
  ordinary_require_eula "$root" || refuse "O1 eula acknowledgement invalid"
  ordinary_require_retry_server_properties "$workspace" "$server_properties" || refuse "O1 loopback server properties invalid"
else
  [[ -d "$root" && ! -L "$root" && "$(readlink -f -- "$root")" == "$root" ]] || refuse "O2 requires an existing canonical retained root"
  ordinary_directory_is_nonempty "$root" || refuse "O2 requires retained nonempty root"
  [[ -d "$o1_receipts" && -f "$o1_manifest" && -f "$host_release" && ! -e "$receipts" && ! -e "$manifest" ]] || refuse "O2 retained root/evidence invalid"
  ordinary_require_eula "$root" || refuse "O2 retained eula acknowledgement invalid"
  ordinary_require_retry_server_properties "$workspace" "$server_properties" || refuse "O2 retained loopback server properties invalid"
  [[ "$(cat "$o1_receipts/generated.exit")" == 0 && "$(cat "$o1_receipts/tee.exit")" == 0 && "$(cat "$o1_receipts/wrapper.exit")" == 0 ]] || refuse "O1 receipt tuple invalid"
  [[ -f "$world_root/level.dat" && -f "$state" && -f "$o1_semantic" && ! -e "$o2_semantic" && "$(ordinary_java_property "$workspace" "$o1_semantic" phase)" == O1 ]] || refuse "O1 provenance invalid"
  level_before=$(ordinary_hash "$world_root/level.dat") || refuse "cannot hash level.dat"
  state_before=$(ordinary_hash "$state") || refuse "cannot hash retained state"
  semantic_before=$(ordinary_hash "$o1_semantic") || refuse "cannot hash retained O1 semantic record"
  server_properties_before=$(ordinary_hash "$server_properties") || refuse "cannot hash retained server properties"
  expected_level=$(ordinary_value "$o1_manifest" level_dat_sha256) || refuse "missing O1 level hash"
  expected_state=$(ordinary_value "$o1_manifest" state_sha256) || refuse "missing O1 state hash"
  expected_semantic=$(ordinary_value "$o1_manifest" o1_semantic_sha256) || refuse "missing O1 semantic hash"
  expected_server_properties=$(ordinary_value "$o1_manifest" server_properties_sha256) || refuse "missing O1 server properties hash"
  released_level=$(ordinary_value "$host_release" level_dat_sha256) || refuse "missing host release level hash"
  released_root=$(ordinary_value "$host_release" root) || refuse "missing host release root"
  released=$(ordinary_value "$host_release" released) || refuse "missing host release status"
  process_observation=$(ordinary_value "$host_release" process_observation) || refuse "missing host process observation"
  cwd_observation=$(ordinary_value "$host_release" cwd_observation) || refuse "missing host cwd observation"
  lock_observation=$(ordinary_value "$host_release" java_file_lock_observation) || refuse "missing host lock observation"
  ordinary_sha256 "$expected_level" && ordinary_sha256 "$expected_state" && ordinary_sha256 "$expected_semantic" && ordinary_sha256 "$expected_server_properties" && ordinary_sha256 "$released_level" || refuse "invalid retained SHA-256 provenance"
  [[ "$level_before" == "$expected_level" && "$state_before" == "$expected_state" && "$semantic_before" == "$expected_semantic" && "$server_properties_before" == "$expected_server_properties" && "$released_level" == "$level_before" && "$released_root" == "$root" && "$released" == 1 && -n "$process_observation" && -n "$cwd_observation" && -n "$lock_observation" ]] || refuse "postshutdown metadata or independent release provenance changed"
fi
mkdir "$receipts" || refuse "cannot claim receipts"; claimed=1
normal_closure=$(ordinary_closure_hash "$normal") || { final=125; exit "$final"; }
legacy_closure=$(ordinary_closure_hash "$legacy") || { final=125; exit "$final"; }
provider=$(ordinary_provider_input "$normal") || { final=125; exit "$final"; }
provider_hash=$(ordinary_hash "$provider") || { final=125; exit "$final"; }
main_tree=$(ordinary_tree_hash "$main_classes") || { final=125; exit "$final"; }
resource_tree=$(ordinary_tree_hash "$main_resources") || { final=125; exit "$final"; }
test_tree=$(ordinary_tree_hash "$test_classes") || { final=125; exit "$final"; }
test_resources_entry=$(ordinary_typed_entry_hash "$test_resources") || { final=125; exit "$final"; }
cd -- "$root" || refuse "cannot cd root"
set +e
/bin/bash -e -- "$generated" 2>&1 | tee "$receipts/full.log"
status=("${PIPESTATUS[@]}")
set -e
printf '%s\n' "${status[0]:-125}" > "$receipts/generated.exit" || exit 125
printf '%s\n' "${status[1]:-125}" > "$receipts/tee.exit" || exit 125
(( ${status[0]:-125} == 0 && ${status[1]:-125} == 0 )) || { final=1; exit "$final"; }
ordinary_require_terminal_log "$receipts/full.log" "$phase" || { final=1; exit "$final"; }
[[ -f "$world_root/level.dat" && -f "$state" && -f "$o1_semantic" ]] || { final=1; exit "$final"; }
if [[ "$phase" == O1 ]]; then [[ "$(ordinary_java_property "$workspace" "$o1_semantic" phase)" == O1 ]]; else [[ -f "$o2_semantic" && "$(ordinary_java_property "$workspace" "$o2_semantic" phase)" == O2 && "$(ordinary_java_property "$workspace" "$o1_semantic" phase)" == O1 ]]; fi || { final=1; exit "$final"; }
level_after=$(ordinary_hash "$world_root/level.dat") || { final=125; exit "$final"; }
state_after=$(ordinary_hash "$state") || { final=125; exit "$final"; }
semantic_after=$(ordinary_hash "$o1_semantic") || { final=125; exit "$final"; }
server_properties_after=$(ordinary_hash "$server_properties") || { final=125; exit "$final"; }
log_hash=$(ordinary_hash "$receipts/full.log") || { final=125; exit "$final"; }
log_bytes=$(wc -c < "$receipts/full.log") || { final=125; exit "$final"; }
ordinary_nonempty_decimal "$log_bytes" || { final=125; exit "$final"; }
[[ -n "$provider" ]] || { final=125; exit "$final"; }
printf 'phase=%s\nroot=%s\nworld_root=%s\nlevel_dat_sha256=%s\nstate_sha256=%s\no1_semantic_sha256=%s\nserver_properties_sha256=%s\nprovider_path=%s\nprovider_sha256=%s\nnormal_closure_sha256=%s\nlegacy_closure_sha256=%s\nmain_tree_sha256=%s\nresource_tree_sha256=%s\ntest_tree_sha256=%s\ntest_resources_entry_sha256=%s\nfull_log_sha256=%s\nfull_log_bytes=%s\ngenerated_exit=0\ntee_exit=0\n' "$phase" "$root" "$world_root" "$level_after" "$state_after" "$semantic_after" "$server_properties_after" "$provider" "$provider_hash" "$normal_closure" "$legacy_closure" "$main_tree" "$resource_tree" "$test_tree" "$test_resources_entry" "$log_hash" "$log_bytes" > "$manifest" || { final=125; exit "$final"; }
final=0
