#!/usr/bin/env bash
# Exclusively claims a fresh execution root for runProviderSafeDeleteCrashWithReceipt.sh,
# BEFORE the launch scripts are even regenerated against it (per MINECRAFT-470
# PR3 review, Jira comments 51596/51604: an emptiness check alone cannot
# prove a directory was freshly claimed by this specific invocation -- and
# a marker's mere presence/shape is not enough either, since a marker
# copied verbatim into a different directory would otherwise still look
# valid. Every marker binds itself to the one exact directory it names.)
#
# This is the ONLY place that creates the execution root. The main wrapper
# never calls mkdir on it; it only verifies the exact claim marker this
# script writes is still present, well-formed, names its own containing
# directory, and reuses this SAME claim nonce as the execution nonce (not
# an unrelated freshly-generated one) -- see root_is_exclusively_claimed
# in runProviderSafeDeleteCrashWithReceipt.sh. Regenerating the launch
# scripts against this SAME path afterward (ModDev's prepare*Run side
# effect) does not disturb this marker -- confirmed empirically: a
# pre-existing file in the configured gameDirectory survives byte-for-byte
# after running createProviderSafeDeleteCrashPhase*LaunchScript against it.
#
# Usage: claimSafeDeleteCrashExecutionRoot.sh <path>
set -uo pipefail

refuse() { printf '%s\n' "refused: $*" >&2; exit 125; }

[[ $# -eq 1 ]] || refuse "usage: claimSafeDeleteCrashExecutionRoot.sh <path>"
path=$1
marker_name=.dynamicverse-safe-delete-crash-execution-root-claim

for tool in sha256sum date hostname mkdir dirname basename; do
  command -v "$tool" >/dev/null 2>&1 || refuse "required tool missing: $tool"
done

# Exclusivity comes from mkdir itself: it atomically fails if the path
# already exists, so there is no separate check-then-create race window.
# No tolerance here for a pre-existing empty directory either -- claiming
# must start from true, provable absence.
[[ ! -e "$path" && ! -L "$path" ]] || refuse "requires an absent, non-symlink path to claim -- never adopt a retained or merely-empty directory: $path"

parent=$(dirname -- "$path")
[[ -d "$parent" && ! -L "$parent" ]] || refuse "the parent directory of '$path' must already exist and must not itself be a symlink: $parent"

# Walk every ancestor (not only the immediate parent, and not only the
# leaf) checking each one is not itself a symlink -- cd/pwd -P would
# silently resolve THROUGH a symlinked ancestor rather than refusing it,
# so this is checked against the UNRESOLVED path chain directly.
ancestor=$parent
previous=""
while [[ "$ancestor" != "$previous" ]]; do
  [[ ! -L "$ancestor" ]] || refuse "a symlinked ancestor is not permitted: $ancestor"
  previous=$ancestor
  ancestor=$(dirname -- "$ancestor")
done

canonical_parent=$(cd -- "$parent" && pwd -P) || refuse "cannot resolve the canonical parent directory of '$path'"
canonical_root="$canonical_parent/$(basename -- "$path")"

mkdir -- "$path" || refuse "cannot create '$path' -- another process may have just claimed it (mkdir is exclusive; this is not a lost race, it is a correct refusal)"

nonce=$(sha256sum <<<"$RANDOM$RANDOM$$$(date +%s%N)" | awk '{print $1}') || { rmdir -- "$path" 2>/dev/null; refuse "cannot derive a claim nonce"; }
marker="$path/$marker_name"
{
  printf 'canonicalRoot=%s\n' "$canonical_root"
  printf 'claimedByHost=%s\n' "$(hostname)"
  printf 'claimedByPid=%s\n' "$$"
  printf 'claimedAtUtc=%s\n' "$(date -u +%FT%TZ)"
  printf 'nonce=%s\n' "$nonce"
} > "$marker" || { rm -f -- "$marker"; rmdir -- "$path" 2>/dev/null; refuse "cannot write the claim marker in '$path'"; }

echo "claimed $canonical_root (marker: $marker_name, nonce: $nonce)"
echo "next: regenerate the launch scripts with -PsafeDeleteCrashGameDirectory=$canonical_root, matching this exact path, then run the wrapper with DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT=$canonical_root"
