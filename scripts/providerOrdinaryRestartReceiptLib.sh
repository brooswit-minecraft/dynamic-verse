#!/usr/bin/env bash
# Helpers for the future O1/O2 ordinary-server receipt wrappers.  They are
# deliberately metadata-only: they never create, reset, or remove a world.

ordinary_refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }

ordinary_hash() {
    local result
    [[ -f "$1" && ! -L "$1" ]] || return 1
    result=$(sha256sum -- "$1" | awk '{print $1}') || return 1
    ordinary_sha256 "$result" || return 1
    printf '%s' "$result"
}

ordinary_sha256() {
    [[ "$1" =~ ^[0-9a-f]{64}$ ]]
}

ordinary_nonempty_decimal() {
    [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

# Do not treat a failed find as an empty/nonempty directory.  The ordinary
# restart wrapper uses these before it has permission to start a server.
ordinary_directory_is_empty() {
    local root=$1 entry
    [[ -d "$root" && ! -L "$root" ]] || return 1
    entry=$(find -P "$root" -mindepth 1 -print -quit) || return 1
    [[ -z "$entry" ]]
}

ordinary_directory_is_nonempty() {
    local root=$1 entry
    [[ -d "$root" && ! -L "$root" ]] || return 1
    entry=$(find -P "$root" -mindepth 1 -print -quit) || return 1
    [[ -n "$entry" ]]
}

ordinary_require_eula() {
    local root=$1 eula=$root/eula.txt
    [[ -f "$eula" && ! -L "$eula" ]] || return 1
    [[ $(grep -Fxc -- 'eula=true' "$eula") == 1 ]] || return 1
}

ordinary_require_retry_server_properties() {
    local workspace=$1 properties=$2
    [[ -f "$properties" && ! -L "$properties" ]] || return 1
    [[ $(ordinary_java_property "$workspace" "$properties" server-ip) == 127.0.0.1 ]] || return 1
    [[ $(ordinary_java_property "$workspace" "$properties" server-port) == 0 ]] || return 1
    [[ $(ordinary_java_property "$workspace" "$properties" level-name) == world ]] || return 1
}

ordinary_tree_hash() {
    local root=$1 item relative digest result
    [[ -d "$root" && ! -L "$root" ]] || return 1
    result=$(find -P "$root" -mindepth 1 -print0 | LC_ALL=C sort -z | while IFS= read -r -d '' item; do
        [[ ! -L "$item" ]] || exit 1
        relative=${item#"$root"/}
        if [[ -d "$item" ]]; then
            printf 'D\0%s\0' "$relative"
        elif [[ -f "$item" ]]; then
            digest=$(ordinary_hash "$item") || exit 1
            printf 'F\0%s\0%s\0' "$relative" "$digest"
        else
            exit 1
        fi
    done | sha256sum | awk '{print $1}') || return 1
    ordinary_sha256 "$result" || return 1
    printf '%s' "$result"
}

ordinary_argfile_entries() {
    local argfile=$1 first line
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

ordinary_typed_entry() {
    local path=$1 digest
    [[ ! -L "$path" ]] || return 1
    if [[ -f "$path" ]]; then
        digest=$(ordinary_hash "$path") || return 1
        printf 'FILE\t%s\t%s\n' "$path" "$digest"
    elif [[ -d "$path" ]]; then
        digest=$(ordinary_tree_hash "$path") || return 1
        printf 'DIRECTORY\t%s\t%s\n' "$path" "$digest"
    else
        # ModDev emits these two providerBridgeGameTest output roots even when
        # that source set is deliberately empty.  Record the absence as part
        # of the closure; any other missing/special entry is fail-closed.
        case "$path" in
            */build/classes/java/providerBridgeGameTest|*/build/resources/providerBridgeGameTest|*/build/resources/test)
                [[ ! -e "$path" && ! -L "$path" ]] || return 1
                printf 'ABSENT\t%s\n' "$path"
                ;;
            *) return 1 ;;
        esac
    fi
}

ordinary_closure_manifest() {
    local argfile=$1 path entries
    # Process substitution hides the producer's status.  Materialize the
    # complete entry list first so an unreadable or malformed argfile cannot
    # become an apparently valid empty/partial closure.
    entries=$(ordinary_argfile_entries "$argfile") || return 1
    [[ -n "$entries" ]] || return 1
    while IFS= read -r path; do
        [[ -n "$path" ]] || return 1
        ordinary_typed_entry "$path" || return 1
    done <<<"$entries"
}

ordinary_closure_hash() {
    local result
    result=$(ordinary_closure_manifest "$1" | sha256sum | awk '{print $1}') || return 1
    ordinary_sha256 "$result" || return 1
    printf '%s' "$result"
}

ordinary_typed_entry_hash() {
    local result
    result=$(ordinary_typed_entry "$1" | sha256sum | awk '{print $1}') || return 1
    ordinary_sha256 "$result" || return 1
    printf '%s' "$result"
}

ordinary_provider_input() {
    local entries providers
    entries=$(ordinary_argfile_entries "$1") || return 1
    providers=$(printf '%s\n' "$entries" | grep -E '/dynamicdimensions[^/]*\.jar$') || return 1
    [[ $(printf '%s\n' "$providers" | sed '/^$/d' | wc -l) == 1 && -f "$providers" && ! -L "$providers" ]] || return 1
    printf '%s' "$providers"
}

ordinary_value() {
    local file=$1 key=$2 result
    [[ -f "$file" && ! -L "$file" ]] || return 1
    result=$(awk -v key="$key" 'index($0, key "=") == 1 { count++; value=substr($0,length(key)+2) } END { if(count != 1 || value == "") exit 1; print value }' "$file") || return 1
    printf '%s' "$result"
}

ordinary_java_property() {
    local workspace=$1 file=$2 key=$3 value
    [[ -f "$file" && ! -L "$file" ]] || return 1
    value=$(/tmp/temurin21/bin/java "$workspace/scripts/ProviderSeededRestartProperties.java" "$file" "$key") || return 1
    [[ -n "$value" ]] || return 1
    printf '%s' "$value"
}

ordinary_require_generated() {
    local generated=$1 normal=$2 legacy=$3 vm=$4 program=$5 log4j=$6 root=$7 phase=$8 main=$9 resources=${10} tests=${11} test_resources=${12}
    [[ -f "$generated" && ! -L "$generated" && -f "$normal" && ! -L "$normal" && -f "$legacy" && ! -L "$legacy" && -f "$vm" && ! -L "$vm" && -f "$program" && ! -L "$program" && -f "$log4j" && ! -L "$log4j" ]] || return 1
    [[ $(grep -Foc -- "cd '$root'; exec" "$generated") == 1 ]] || return 1
    [[ $(grep -Foc -- "'@$normal'" "$generated") == 1 && $(grep -Foc -- "'@$vm'" "$generated") == 1 && $(grep -Foc -- "'@$program'" "$generated") == 1 ]] || return 1
    [[ $(grep -Fxc -- "-DlegacyClassPath.file=$legacy" "$vm") == 1 && $(grep -Fxc -- "-Dlog4j2.configurationFile=$log4j" "$vm") == 1 ]] || return 1
    [[ $(grep -Fxc -- "-Ddynamicverse.providerOrdinaryRestartPhase=$phase" "$vm") == 1 && $(grep -Fc -- '-Ddynamicverse.providerOrdinaryRestartPhase=' "$vm") == 1 ]] || return 1
    [[ $(grep -Fc -- '-Dneoforge.enabledGameTestNamespaces=' "$vm") == 0 ]] || return 1
    [[ $(grep -Foc -- "'-Dfml.modFolders=dynamicverse%%$main:dynamicverse%%$resources:dynamicverse%%$tests:dynamicverse%%$test_resources'" "$generated") == 1 ]] || return 1
    [[ $(head -n 1 -- "$normal") == -classpath ]] || return 1
}

ordinary_require_terminal_log() {
    local log=$1 phase=$2
    [[ -f "$log" && ! -L "$log" ]] || return 1
    local marker
    marker=$([[ "$phase" == O1 ]] && printf '%s' 'O1 created READY' || printf '%s' 'O2 loaded ordinary metadata')
    [[ $(grep -Fc -- "$marker" "$log") == 1 && $(grep -Fc -- 'Stopping server' "$log") -ge 1 ]] || return 1
    [[ $(grep -Fci -- 'ordinary restart observation failed' "$log") == 0 && $(grep -Fci -- 'ordinary restart setup failed' "$log") == 0 ]] || return 1
}
