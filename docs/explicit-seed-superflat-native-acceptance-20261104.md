# Explicit-seed SUPERFLAT: native acceptance (2026-11-04)

Per MINECRAFT-470 review (PR #3, https://github.com/brooswit-minecraft/dynamic-verse/pull/3#issuecomment-6633684354):
the source-review gate on the already-published `ProviderExplicitSuperflatGameTest`
was cleared for one bounded native execution, in a verified fresh, exclusively
test-owned disposable profile, using the **corrected** PR2 provider classes/jar
explicitly selected (not the stale `build.gradle` defaults, which still point
at the older, uncorrected candidate).

## Preconditions verified before running

- `ps aux` showed no overlapping java/gametest process.
- `run/m470-explicit-superflat-disposable-corrected-20261102` did not exist
  (truly fresh, never used before this run).
- `build/moddev/m470-explicit-superflat-disposable-corrected-20261102-receipts`
  did not exist (no stale receipts to collide with).

## Corrected provider inputs explicitly selected

Built from `provider-patches/dynamicdimensions-reviewed-seed-close.patch`
applied to the pristine pin (same recipe as `docs/provider-source-reproducibility.md`,
PR #2), **including** the admission-order correction from that PR's `0d25446`:

```sh
JAVA_HOME=/tmp/temurin21 ./gradlew createProviderExplicitSuperflatGameTestLaunchScript --no-daemon \
  -PacceptedDynamicDimensionsJar=<path-to-corrected>/dynamicdimensions-neoforge-0.10.0+ecfbdf69-dirty.jar \
  -PreviewedDynamicDimensionsClasses=<path-to-corrected>/neoforge/build/classes/java/main
```

The corrected jar's SHA-256 is `5c7afbc7061b94ccbe3f3fa903749fc37b597c665de95a28df54f743d93de78f`
(the exact one documented and reviewed on PR #2). Confirmed selected, not the
stale default, by the launch script's own generated classpath referencing
that exact jar path, and by the server's own mod-list log line below.

## Execution and receipts

Ran the generated script directly (no pre-existing wrapper for this
newly-registered run existed; captured the same generated/tee/wrapper exit
triple by hand): `0 0 0`. Full log 10,815 bytes, SHA-256
`c004e6b3ec71cf365f27ee00b06269078b4ed8c89149871a5da3da90042271b7`.

Server log: mod list shows `Dynamic Dimensions 0.10.0+ecfbdf69-dirty` — the
corrected build's own version string, confirming the corrected jar was
actually loaded, not the old `4b09389e-dirty` candidate. "1 tests are now
running" → the fixture's own line `EXPLICIT_SUPERFLAT_ACCEPTED seed=1511506142
createCallbacks=1 closeCallbacks=1 freshReplay=true` → "All 1 required tests
passed :)" → normal save (including the real created dimension
`dynamicverse:explicit_flat_review`) and clean "Game test server shutting down".

Post-run: no lingering java/GameTest process; `lsof` on the world's
`session.lock` shows no holder.

## Scope of this evidence

This fixture creates, observes, and replays its own freshly created,
disposable verse (`explicit_flat_review`) — it does not touch any retained or
user world, and performs no deletion. It is native, single-execution,
corrected-provider acceptance for the explicit-seed SUPERFLAT create/reload
path specifically. It is not a repeat of, and does not supersede, any other
M470 acceptance gate (safe-delete, duplicate-admission, ordinary restart);
those remain exactly as previously reported.
