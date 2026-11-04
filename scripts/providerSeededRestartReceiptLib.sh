#!/usr/bin/env bash
# Shared, future-only validation helpers for the two explicit-seed restart phases.
# Callers set workspace and use refuse() for their owned receipt policy.

seed_restart_file_hash() {
    [[ -f "$1" && ! -L "$1" ]] || return 1
    sha256sum -- "$1" || return 1
}

seed_restart_tree_hash() {
    local root=$1 item relative digest result
    [[ -d "$root" && ! -L "$root" ]] || return 1
    result=$( {
        # Include directories, including empty ones, and reject every link or
        # special entry.  NUL sorting makes the representation deterministic.
        find -P "$root" -mindepth 1 -print0 | LC_ALL=C sort -z | while IFS= read -r -d '' item; do
            [[ ! -L "$item" ]] || exit 1
            relative=${item#"$root"/}
            if [[ -d "$item" ]]; then
                printf 'D\0%s\0' "$relative"
            elif [[ -f "$item" ]]; then
                digest=$(sha256sum -- "$item") || exit 1
                printf 'F\0%s\0%s\0' "$relative" "${digest%% *}"
            else
                exit 1
            fi
        done | { sha256sum || exit 1; } | awk '{print $1}'
    } ) || return 1
    [[ -n "$result" ]] || return 1
    printf '%s' "$result"
}

seed_restart_declared_absent() {
    case "$1" in
        "$workspace/build/classes/java/providerBridgeGameTest"|"$workspace/build/resources/providerBridgeGameTest"|"$workspace/build/resources/test") return 0 ;;
        *) return 1 ;;
    esac
}

seed_restart_typed_entry() {
    local path=$1 digest
    [[ ! -L "$path" ]] || return 1
    if [[ -f "$path" ]]; then
        digest=$(seed_restart_file_hash "$path") || return 1
        printf 'FILE\t%s\t%s\n' "$path" "${digest%% *}"
    elif [[ -d "$path" ]]; then
        digest=$(seed_restart_tree_hash "$path") || return 1
        printf 'DIRECTORY\t%s\t%s\n' "$path" "$digest"
    elif [[ ! -e "$path" && ! -L "$path" ]] && seed_restart_declared_absent "$path"; then
        printf 'ABSENT\t%s\t-\n' "$path"
    else
        return 1
    fi
}

seed_restart_argfile_entries() {
    local argfile=$1 line first
    [[ -f "$argfile" && ! -L "$argfile" ]] || return 1
    first=$(head -n 1 -- "$argfile") || return 1
    if [[ "$first" == -classpath ]]; then
        line=$(sed -n '2p' -- "$argfile") || return 1
        [[ -n "$line" ]] || return 1
        tr ':' '\n' <<<"$line"
    else
        sed '/^$/d' -- "$argfile" || return 1
    fi
}

seed_restart_closure_manifest() {
    local argfile=$1
    seed_restart_argfile_entries "$argfile" | while IFS= read -r path; do
        [[ -n "$path" ]] || exit 1
        seed_restart_typed_entry "$path" || exit 1
    done
}

seed_restart_closure_hash() {
    local result
    result=$(seed_restart_closure_manifest "$1" | { sha256sum || exit 1; } | awk '{print $1}') || return 1
    [[ -n "$result" ]] || return 1
    printf '%s' "$result"
}

seed_restart_provider_input() {
    local argfile=$1 providers
    providers=$(seed_restart_argfile_entries "$argfile" | grep -E '/dynamicdimensions[^/]*\.jar$') || return 1
    [[ $(printf '%s\n' "$providers" | sed '/^$/d' | wc -l) == 1 ]] || return 1
    [[ -f "$providers" && ! -L "$providers" ]] || return 1
    printf '%s' "$providers"
}

seed_restart_modfolder_manifest() {
    local generated=$1 argument folders index=0 expected path
    argument=$(sed -n "s/.*'-Dfml\\.modFolders=\\([^']*\\)'.*/\\1/p" -- "$generated") || return 1
    [[ $(printf '%s\n' "$argument" | sed '/^$/d' | wc -l) == 1 ]] || return 1
    local -a actual=( ${argument//:/ } )
    local -a wanted=(
        "dynamicverse%%$workspace/build/classes/java/main"
        "dynamicverse%%$workspace/build/resources/main"
        "dynamicverse%%$workspace/build/classes/java/test"
        "dynamicverse%%$workspace/build/resources/test"
    )
    [[ ${#actual[@]} == ${#wanted[@]} ]] || return 1
    for expected in "${wanted[@]}"; do
        [[ "${actual[$index]}" == "$expected" ]] || return 1
        path=${expected#dynamicverse%%}
        seed_restart_typed_entry "$path" || return 1
        index=$((index + 1))
    done
}

seed_restart_modfolder_hash() {
    local result
    result=$(seed_restart_modfolder_manifest "$1" | { sha256sum || exit 1; } | awk '{print $1}') || return 1
    [[ -n "$result" ]] || return 1
    printf '%s' "$result"
}

seed_restart_require_selector() {
    local vm=$1 phase=$2 expected_fixture=$3 fixture_class=$4
    [[ $(grep -Fxc -- "-Ddynamicverse.providerSeededRestartPhase=$phase" "$vm") == 1 ]] || return 1
    [[ $(grep -Fc -- '-Ddynamicverse.providerSeededRestartPhase=' "$vm") == 1 ]] || return 1
    [[ $(grep -Fxc -- '-Dneoforge.enabledGameTestNamespaces=dynamicverse' "$vm") == 1 ]] || return 1
    [[ $(grep -Fc -- '-Dneoforge.enabledGameTestNamespaces=' "$vm") == 1 ]] || return 1
    [[ -f "$fixture_class" && ! -L "$fixture_class" ]] || return 1
    # The profile-specific DynamicVerse switch registers this one class; bind
    # the exact loaded fixture class into the phase manifest before execution.
    printf 'phase=%s\nnamespace=dynamicverse\nfixture=%s\n' "$phase" "$expected_fixture"
    seed_restart_typed_entry "$fixture_class"
    seed_restart_typed_entry "$workspace/build/classes/java/main/io/github/brooswitminecraft/dynamicverse/DynamicVerse.class"
}

seed_restart_require_generated_inputs() {
    local generated=$1 normal=$2 legacy=$3 vm=$4 program=$5 log4j=$6 expected_root=$7
    [[ -f "$generated" && ! -L "$generated" && -f "$normal" && ! -L "$normal" && -f "$legacy" && ! -L "$legacy" && -f "$vm" && ! -L "$vm" && -f "$program" && ! -L "$program" && -f "$log4j" && ! -L "$log4j" ]] || return 1
    [[ $(grep -Foc -- "cd '$expected_root'; exec" "$generated") == 1 ]] || return 1
    [[ $(grep -Foc -- "'@$normal'" "$generated") == 1 ]] || return 1
    [[ $(grep -Foc -- "'@$vm'" "$generated") == 1 ]] || return 1
    [[ $(grep -Foc -- "'@$program'" "$generated") == 1 ]] || return 1
    [[ $(grep -Foc -- "'net.neoforged.devlaunch.Main'" "$generated") == 1 ]] || return 1
    [[ $(grep -Fxc -- "-DlegacyClassPath.file=$legacy" "$vm") == 1 ]] || return 1
    [[ $(grep -Fxc -- "-Dlog4j2.configurationFile=$log4j" "$vm") == 1 ]] || return 1
    [[ $(head -n 1 -- "$normal") == -classpath ]] || return 1
    [[ $(head -n 1 -- "$program") == '# Main Class' ]] || return 1
}

seed_restart_manifest_header_value() {
    local manifest=$1 key=$2 found
    [[ -f "$manifest" && ! -L "$manifest" ]] || return 1
    # Only the pre-selector header is authoritative.  The selector section
    # intentionally repeats phase/namespace/fixture to bind the launch choice.
    # Require one well-formed bounded selector section so a malformed manifest
    # cannot silently turn a later section into header data.
    found=$(awk -v key="$key" '
        BEGIN { header = 1; selector_begin = 0; selector_end = 0; in_selector = 0; bad = 0 }
        $0 == "selector_manifest_begin" {
            if (!header || ++selector_begin != 1) bad = 1
            header = 0
            in_selector = 1
            next
        }
        $0 == "selector_manifest_end" {
            if (!in_selector || ++selector_end != 1) bad = 1
            in_selector = 0
            next
        }
        {
            if (header && index($0, key "=") == 1) print substr($0, length(key) + 2)
        }
        END {
            if (header || selector_begin != 1 || selector_end != 1 || in_selector || bad) exit 1
        }
    ' "$manifest") || return 1
    [[ $(printf '%s\n' "$found" | sed '/^$/d' | wc -l) == 1 ]] || return 1
    printf '%s' "$found"
}

# Read one exact key from a small, line-oriented, independently retained
# verification record.  This is deliberately separate from Phase-A's missing
# manifest: a post-run verification may describe the old receipt, but it must
# never rewrite or impersonate it.
seed_restart_verification_value() {
    local record=$1 key=$2 found
    [[ -f "$record" && ! -L "$record" ]] || return 1
    found=$(awk -v key="$key" '
        index($0, key "=") == 1 { count++; value = substr($0, length(key) + 2) }
        END { if (count != 1 || value == "") exit 1; print value }
    ' "$record") || return 1
    printf '%s' "$found"
}

seed_restart_properties_value() {
    local state=$1 key=$2 value
    [[ -f "$state" && ! -L "$state" ]] || return 1
    value=$(/tmp/temurin21/bin/java "$workspace/scripts/ProviderSeededRestartProperties.java" "$state" "$key") || return 1
    [[ -n "$value" ]] || return 1
    printf '%s' "$value"
}

seed_restart_require_terminal_log() {
    local log=$1 expected_fixture=$2
    [[ -f "$log" && ! -L "$log" ]] || return 1
    [[ $(grep -Fc -- '========= 1 GAME TESTS COMPLETE' "$log") == 1 ]] || return 1
    [[ $(grep -Fc -- 'All 1 required tests passed :)' "$log") == 1 ]] || return 1
    [[ $(grep -Fc -- 'Game test server shutting down' "$log") == 1 ]] || return 1
    [[ $(grep -Fc -- 'required tests failed' "$log") == 0 ]] || return 1
    # The exact fixture is bound before launch through selector+class identity;
    # the vanilla progress log does not emit TestFunction.testName on success.
    [[ -n "$expected_fixture" ]] || return 1
}

# Validate the exceptional transparent bridge from the preserved original
# Phase-A evidence to a future Phase-B preflight.  The record must continue to
# say wrapper=1; successful GameTest evidence is carried by the original log,
# not manufactured by this helper.
seed_restart_require_transparent_phase_a_with_expected() {
    local phase_a=$1 postrun=$2 game_root=$3 world_root=$4 state=$5 expected_log_hash=$6 expected_log_bytes=$7 expected_state_hash=$8 expected_state_record=$9 log_hash log_bytes state_hash state_record
    [[ -d "$phase_a" && ! -L "$phase_a" && -f "$postrun" && ! -L "$postrun" ]] || return 1
    [[ -d "$game_root" && ! -L "$game_root" && "$(readlink -f -- "$game_root")" == "$game_root" ]] || return 1
    [[ -d "$world_root" && ! -L "$world_root" && -f "$state" && ! -L "$state" ]] || return 1
    [[ "$(cat -- "$phase_a/generated.exit")" == 0 && "$(cat -- "$phase_a/tee.exit")" == 0 && "$(cat -- "$phase_a/wrapper.exit")" == 1 ]] || return 1
    seed_restart_require_terminal_log "$phase_a/full.log" createseedednormalpersistandclose || return 1
    log_hash=$(seed_restart_file_hash "$phase_a/full.log") || return 1
    log_hash=${log_hash%% *}; log_bytes=$(wc -c < "$phase_a/full.log") || return 1
    [[ "$log_hash" == "$expected_log_hash" && "$log_bytes" == "$expected_log_bytes" ]] || return 1
    [[ "$(seed_restart_verification_value "$postrun" kind)" == offline-post-run-verification && "$(seed_restart_verification_value "$postrun" scope)" == future-wrapper-properties-parser-only && "$(seed_restart_verification_value "$postrun" phase_a_log_sha256)" == "$log_hash" && "$(seed_restart_verification_value "$postrun" phase_a_log_bytes)" == "$log_bytes" && "$(seed_restart_verification_value "$postrun" phase_a_generated_exit)" == 0 && "$(seed_restart_verification_value "$postrun" phase_a_tee_exit)" == 0 && "$(seed_restart_verification_value "$postrun" phase_a_wrapper_exit)" == 1 && "$(seed_restart_verification_value "$postrun" manifest_created)" == false && "$(seed_restart_verification_value "$postrun" owner_release_created)" == false && "$(seed_restart_verification_value "$postrun" phase_b_launched)" == false ]] || return 1
    state_hash=$(seed_restart_file_hash "$state") || return 1; state_hash=${state_hash%% *}
    [[ "$state_hash" == "$expected_state_hash" && "$state_hash" == "$(seed_restart_verification_value "$postrun" retained_state_sha256)" ]] || return 1
    state_record=$(seed_restart_properties_value "$state" 'ready.dynamicverse:native_seeded_restart') || return 1
    [[ "$state_record" == "$expected_state_record" && "$(seed_restart_verification_value "$postrun" decoded_key)" == ready.dynamicverse:native_seeded_restart && "$(seed_restart_verification_value "$postrun" decoded_value)" == "$state_record" ]] || return 1
    ! grep -q '^operation\.' "$state"
}

seed_restart_require_transparent_phase_a() {
    seed_restart_require_transparent_phase_a_with_expected "$1" "$2" "$3" "$4" "$5" \
        d761dae87cc6f5047b29dc03fc097703e377c81ab39bc3d90e7dd4e4a31943a9 21799 \
        d5cb872eec17c56ef8cbbe417c3dbab30c6ebcd87f333f2912bc33f2e26a4e07 \
        'NORMAL|9123417733|9123417733|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES'
}

# A new record may bind the preserved storage bytes to the original run, but
# it must say what it cannot establish: region bytes are only a sentinel
# witness; the Phase-B fixture must still verify the lamp after provider load.
seed_restart_require_postrun_world_evidence() {
    local record=$1 game_root=$2 world_root=$3 state=$4 original_log_hash=$5 world_tree region region_hash
    [[ -f "$record" && ! -L "$record" && -d "$game_root" && ! -L "$game_root" && "$(readlink -f -- "$game_root")" == "$game_root" ]] || return 1
    [[ -d "$world_root" && ! -L "$world_root" && -f "$state" && ! -L "$state" ]] || return 1
    [[ -n "$(find "$game_root" -mindepth 1 -print -quit)" ]] || return 1
    world_tree=$(seed_restart_tree_hash "$world_root") || return 1
    region="$world_root/dimensions/dynamicverse/native_seeded_restart/region/r.0.0.mca"
    region_hash=$(seed_restart_file_hash "$region") || return 1; region_hash=${region_hash%% *}
    [[ "$(seed_restart_verification_value "$record" kind)" == transparent-post-run-world-evidence && "$(seed_restart_verification_value "$record" game_root)" == "$game_root" && "$(seed_restart_verification_value "$record" world_root)" == "$world_root" && "$(seed_restart_verification_value "$record" state_path)" == "$state" && "$(seed_restart_verification_value "$record" dimension_key)" == dynamicverse:native_seeded_restart && "$(seed_restart_verification_value "$record" original_phase_a_log_sha256)" == "$original_log_hash" && "$(seed_restart_verification_value "$record" world_tree_sha256)" == "$world_tree" && "$(seed_restart_verification_value "$record" sentinel_region_relative)" == dimensions/dynamicverse/native_seeded_restart/region/r.0.0.mca && "$(seed_restart_verification_value "$record" sentinel_region_path)" == "$region" && "$(seed_restart_verification_value "$record" sentinel_region_sha256)" == "$region_hash" && "$(seed_restart_verification_value "$record" sentinel_claim)" == post-load-verification-required ]] || return 1
}

seed_restart_require_host_release() {
    local record=$1 log_hash=$2 log_bytes=$3 game_root=$4 world_root=$5 state_hash=$6
    [[ -f "$record" && ! -L "$record" && "$(seed_restart_verification_value "$record" phase_a_log_sha256)" == "$log_hash" && "$(seed_restart_verification_value "$record" phase_a_log_bytes)" == "$log_bytes" && "$(seed_restart_verification_value "$record" phase_a_generated_exit)" == 0 && "$(seed_restart_verification_value "$record" phase_a_tee_exit)" == 0 && "$(seed_restart_verification_value "$record" phase_a_wrapper_exit)" == 1 && "$(seed_restart_verification_value "$record" game_root)" == "$game_root" && "$(seed_restart_verification_value "$record" world_root)" == "$world_root" && "$(seed_restart_verification_value "$record" state_sha256)" == "$state_hash" && -n "$(seed_restart_verification_value "$record" observer)" && -n "$(seed_restart_verification_value "$record" observed_at)" && -n "$(seed_restart_verification_value "$record" host_process_observation)" && -n "$(seed_restart_verification_value "$record" host_cwd_observation)" && -n "$(seed_restart_verification_value "$record" java_file_lock_observation)" && "$(seed_restart_verification_value "$record" released)" == 1 ]]
}

seed_restart_require_snapshot_file() {
    local label=$1 path=$2 expected=$3 actual
    actual=$(seed_restart_file_hash "$path") || return 1
    [[ "${actual%% *}" == "$expected" ]] || return 1
}
