#!/usr/bin/env bash
# Exclusively claims a fresh execution root for runProviderSafeDeleteCrashWithReceipt.sh,
# BEFORE the launch scripts are even regenerated against it (per MINECRAFT-470
# PR3 review, Jira comment 51596: an emptiness check alone cannot prove a
# directory was freshly claimed by this specific invocation -- Gradle's own
# prepare*Run task leaves an untouched directory looking identically "empty"
# whether it was just claimed here or is some unrelated retained root that
# happens to be empty for a different reason).
#
# This is the ONLY place that creates the execution root. The main wrapper
# never calls mkdir on it; it only verifies the exact claim marker this
# script writes is still present and well-formed (see
# root_is_exclusively_claimed in runProviderSafeDeleteCrashWithReceipt.sh).
# Regenerating the launch scripts against this SAME path afterward (ModDev's
# prepare*Run side effect) does not disturb this marker -- confirmed
# empirically: a pre-existing file in the configured gameDirectory survives
# byte-for-byte after running createProviderSafeDeleteCrashPhase*LaunchScript
# against it.
#
# Usage: claimSafeDeleteCrashExecutionRoot.sh <path>
set -uo pipefail

refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }

[[ $# -eq 1 ]] || refuse "usage: claimSafeDeleteCrashExecutionRoot.sh <path>"
path=$1
marker_name=.dynamicverse-safe-delete-crash-execution-root-claim

for tool in sha256sum date hostname mkdir; do
  command -v "$tool" >/dev/null 2>&1 || refuse "required tool missing: $tool"
done

# Exclusivity comes from mkdir itself: it atomically fails if the path
# already exists, so there is no separate check-then-create race window.
# No tolerance here for a pre-existing empty directory either -- claiming
# must start from true, provable absence.
[[ ! -e "$path" && ! -L "$path" ]] || refuse "requires an absent, non-symlink path to claim -- never adopt a retained or merely-empty directory: $path"
mkdir -- "$path" || refuse "cannot create '$path' -- another process may have just claimed it (mkdir is exclusive; this is not a lost race, it is a correct refusal)"

nonce=$(sha256sum <<<"$RANDOM$RANDOM$$$(date +%s%N)" | awk '{print $1}') || { rmdir -- "$path" 2>/dev/null; refuse "cannot derive a claim nonce"; }
marker="$path/$marker_name"
{
  printf 'claimedByHost=%s\n' "$(hostname)"
  printf 'claimedByPid=%s\n' "$$"
  printf 'claimedAtUtc=%s\n' "$(date -u +%FT%TZ)"
  printf 'nonce=%s\n' "$nonce"
} > "$marker" || { rm -f -- "$marker"; rmdir -- "$path" 2>/dev/null; refuse "cannot write the claim marker in '$path'"; }

echo "claimed $path (marker: $marker_name, nonce: $nonce)"
echo "next: regenerate the launch scripts with -PsafeDeleteCrashGameDirectory=$path, matching this exact path, then run the wrapper with DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT=$path"
