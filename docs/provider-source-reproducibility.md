# Provider source reproducibility

Prior review (PR #2, https://github.com/brooswit-minecraft/dynamic-verse/pull/2#issuecomment-6619836681)
found that README only pinned the reviewed provider by a candidate-jar checksum, because the
classes build at `/tmp/dynamicdimensions-composite` on `minecraft-470-local-quiesce-close` had
further uncommitted, untracked changes on top of commit `4b09389` with no clean replayable ref.

This recovers a fully reproducible recipe instead: a patch file against the pristine pinned
upstream commit, verified in this session to apply cleanly, compile, pass its own unit tests, and
produce a jar whose actual class files match the previously pinned candidate except for one
corrected defect (below).

## Recipe

1. Clone `TeamGalacticraft/DynamicDimensions`, branch `minecraft/1.21`, and check out commit
   `ecfbdf694d4f4c103404a77858d00c54a91b8020` (the pin already recorded in
   `docs/feasibility-provenance.md`).
2. Apply `provider-patches/dynamicdimensions-reviewed-seed-close.patch` (SHA-256
   `3b952a44cbd7ab6ffaaa7a4ca39b9caa6bd6bb4b43bbd00fc18cfb9963109e50`) with `git apply --binary`
   from the checkout root. It adds the reviewed `beginQuiesceAndClose` lifecycle API
   (`QuiesceAndClose*` classes, the `MinecraftServerMixin`/`ServerLevelMixin` hooks), the
   explicit-seed API (`ExplicitSeedDimensionRegistry`, `DimensionSeedOwnership`,
   `SeedOwnershipTable`), their own unit tests, and the admission-order correction below.
3. `pluginManagement.includeBuild` in `settings.gradle.kts` resolves `mojarnSource` from a Gradle
   property; point it at a local `TeamGalacticraft/mojarn` checkout (`docs/feasibility-provenance.md`
   records the historical pin `beb2addf7ec99a398b6a243cdcec73f4f33f6b01`) and build offline:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :common:test :neoforge:build \
  -PmojarnSource=/path/to/mojarn-checkout --offline --no-daemon
```

Run once without `--offline` first if the Gradle cache is cold.

## Admission-order correction (found in review of this patch, at PR2 head 08457ba)

Independent review (https://github.com/brooswit-minecraft/dynamic-verse/pull/2#issuecomment-6632510117)
found that the recovered patch's `MinecraftServerMixin.dynamicdimensions$removeLevel` guard against
a pending/quarantined quiesce-and-close ran *after* the unchanged legacy
`DynamicDimensionRegistryImpl.deleteDynamicDimension`/`unloadDynamicDimension` had already mutated
physics state (and, for delete, `dimensionProperties`) and unconditionally returned `true`. A
legacy delete/unload call racing an admitted close could mutate state, get silently refused inside
`removeLevel`, and still report success to its caller.

Fix: added a side-effect-free `DynamicDimensionProvider.dynamicdimensions$canRemoveLevel(key)`
admission check, called from `deleteDynamicDimension`/`unloadDynamicDimension` *before* either
mutates any physics/property state; `removeLevel`'s own guard is retained as defense in depth, not
the only check. Regression: `DynamicDimensionsGametest.pendingQuiesceAndCloseRefusesLegacyDeleteAndUnloadBeforeMutation`
(native `@GameTest`, in `provider-patches/`) admits a close for a source with non-default
`DynamicDimensionProperties`, then — before the queue drains — calls both legacy entry points and
asserts: both refuse (`false`), physics/properties and the loaded level are unchanged, the admitted
close still delivers exactly one `SAVED_AND_CLOSED` terminal, and the level is then actually gone.
(The reviewer also asked for a quarantined-source variant; that needs a deterministic in-world
save/close failure to trigger quarantine and was not added here — flagged as not covered, not
fabricated.)

## Verification performed this session

- `git apply --check` against a **fresh, otherwise-untouched copy** of the pinned upstream
  commit: clean, no conflicts. Re-verified against a second, independent fresh copy after the
  admission-order correction above.
- `git apply` + `:neoforge:compileJava`: `BUILD SUCCESSFUL` (checked twice, independently).
- `:common:test`: 18 tests / 5 suites / 0 failures / 0 errors / 0 skipped (the patch's own
  `QuiesceAndCloseBridgeTest`, `QuiesceAndCloseQueueTest`, `QuiesceAndCloseStateMachineTest`,
  `SeedOwnershipStateTest`, `SeedOwnershipTableTest`) — unchanged by the correction, as expected
  (it touches the mixin/registry, not these internal step classes).
- Native `:neoforge:gameTest` run (this provider's own `gameTestServer` run, registering
  `DynamicDimensionsGametest`, disposable `neoforge/run` game directory): exit `0`, full log
  114,631 bytes, SHA-256 `bb04b30a99553de2d5d65809244b671200ac3c9527e96dfa125391e072a63ccc`.
  Server log: "7 tests are now running" → "All 7 required tests passed :)" → normal save/shutdown,
  including the new `level_6` regression above. No lingering process afterward.
- `:neoforge:build` produced `dynamicdimensions-neoforge-0.10.0+ecfbdf69-dirty.jar`, SHA-256
  `5c7afbc7061b94ccbe3f3fa903749fc37b597c665de95a28df54f743d93de78f`. Unzipped and diffed class-by-class
  against the previously pinned candidate (`dynamicdimensions-neoforge-0.10.0+4b09389e-dirty.candidate-51101.jar`,
  SHA-256 `50b7b75a0751f9774a0586926a9e441512fb62fc815f992052aed9647fc3b30e`, still the default for
  `acceptedDynamicDimensionsJar`/`reviewedDynamicDimensionsClasses` in `build.gradle`):
  **the provider changed, so this is no longer byte-identical — that is expected, not a regression
  in the recipe.** Class-comparison manifest:
  - Changed (the fix + its own test fixture, exactly as expected):
    `dev/galacticraft/dynamicdimensions/impl/accessor/DynamicDimensionProvider.class`,
    `dev/galacticraft/dynamicdimensions/impl/DynamicDimensionRegistryImpl.class`,
    `dev/galacticraft/dynamicdimensions/impl/mixin/MinecraftServerMixin.class` (+ its `$1` anonymous
    class), `dev/galacticraft/dynamicdimensions/impl/gametest/DynamicDimensionsGametest.class`
    (+ its `$1`/`$GenerationEvidence`/`$GenerationGrid` nested classes — constant-pool/inner-class
    numbering shift from the added test, not independent logic changes).
  - Unchanged (byte-identical to the old candidate): every other class, including all
    `QuiesceAndClose*`, `SeedOwnership*`, `ExplicitSeedDimensionRegistry`, and
    `ServerLevelMixin`/`DimensionSeedOwnership` classes — only `META-INF/MANIFEST.MF` (timestamp),
    `neoforge.mods.toml`, and `pack.mcmeta` (version-string metadata) differ there, as before.

No runtime world, native GameTest, or this repository's own fixture ran as part of the first
(pre-correction) verification pass — only the external provider's own build/test tasks, against a
disposable scratch copy of the pristine pin. The corrected pass above did run this provider's own
native GameTest, in its own disposable scratch game directory. The original
`/tmp/dynamicdimensions-source` pin and `/tmp/dynamicdimensions-composite` adaptation were read,
never modified, throughout.

## Provenance caveat

This patch file was found already prepared, untracked, under `provider-patches/` in a local-only
`minecraft-470-safe-delete-continuation` checkout; it carried no commit, doc, or README reference
recording who produced it, when, or against what validation. This document, and the diff against
the pinned candidate jar above, is this session's own independent verification of what the patch
actually does — treat it as newly verified, not as a previously-reviewed artifact.

## Still open

`build.gradle`'s `reviewedDynamicDimensionsClasses`/`acceptedDynamicDimensionsJar` defaults still
point at the dirty `/tmp/dynamicdimensions-composite` build output on this machine. Repointing them
at a build produced by this recipe (or committing the patch's source into a dedicated branch of the
provider's own repository) is follow-up, not done by this commit — this document only establishes
that a clean, reproducible alternative now exists and has been verified to match.
