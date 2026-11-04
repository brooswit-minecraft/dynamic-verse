# Provider source reproducibility

Prior review (PR #2, https://github.com/brooswit-minecraft/dynamic-verse/pull/2#issuecomment-6619836681)
found that README only pinned the reviewed provider by a candidate-jar checksum, because the
classes build at `/tmp/dynamicdimensions-composite` on `minecraft-470-local-quiesce-close` had
further uncommitted, untracked changes on top of commit `4b09389` with no clean replayable ref.

This recovers a fully reproducible recipe instead: a patch file against the pristine pinned
upstream commit, verified in this session to apply cleanly, compile, pass its own unit tests, and
produce a jar whose actual class files are byte-identical to the previously pinned candidate.

## Recipe

1. Clone `TeamGalacticraft/DynamicDimensions`, branch `minecraft/1.21`, and check out commit
   `ecfbdf694d4f4c103404a77858d00c54a91b8020` (the pin already recorded in
   `docs/feasibility-provenance.md`).
2. Apply `provider-patches/dynamicdimensions-reviewed-seed-close.patch` (SHA-256
   `e371df2b3f2879321b7d21fd47f7ffde60fa5b3a5b49b94b09b0ef0d1edc7fa1`) with `git apply` from the
   checkout root. It adds the reviewed `beginQuiesceAndClose` lifecycle API (`QuiesceAndClose*`
   classes, the `MinecraftServerMixin`/`ServerLevelMixin` hooks) and the explicit-seed API
   (`ExplicitSeedDimensionRegistry`, `DimensionSeedOwnership`, `SeedOwnershipTable`), plus their
   own unit tests.
3. `pluginManagement.includeBuild` in `settings.gradle.kts` resolves `mojarnSource` from a Gradle
   property; point it at a local `TeamGalacticraft/mojarn` checkout (`docs/feasibility-provenance.md`
   records the historical pin `beb2addf7ec99a398b6a243cdcec73f4f33f6b01`) and build offline:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew :common:test :neoforge:build \
  -PmojarnSource=/path/to/mojarn-checkout --offline --no-daemon
```

Run once without `--offline` first if the Gradle cache is cold.

## Verification performed this session

- `git apply --check` against a **fresh, otherwise-untouched copy** of the pinned upstream
  commit: clean, no conflicts.
- `git apply` + `:neoforge:compileJava`: `BUILD SUCCESSFUL`.
- `:common:test`: 18 tests / 5 suites / 0 failures / 0 errors / 0 skipped (the patch's own
  `QuiesceAndCloseBridgeTest`, `QuiesceAndCloseQueueTest`, `QuiesceAndCloseStateMachineTest`,
  `SeedOwnershipStateTest`, `SeedOwnershipTableTest`).
- `:neoforge:build` produced `dynamicdimensions-neoforge-0.10.0+ecfbdf69-dirty.jar`, SHA-256
  `56bb51e9a74afbd4c006a5206e9b330123a673644557fb64484d4ac79cb85cff`. Unzipped and diffed against
  the previously pinned candidate (`dynamicdimensions-neoforge-0.10.0+4b09389e-dirty.candidate-51101.jar`,
  SHA-256 `50b7b75a0751f9774a0586926a9e441512fb62fc815f992052aed9647fc3b30e`, still the default for
  `acceptedDynamicDimensionsJar`/`reviewedDynamicDimensionsClasses` in `build.gradle`): every class
  file is byte-identical; only `META-INF/MANIFEST.MF` (build timestamp), `neoforge.mods.toml`, and
  `pack.mcmeta` (version-string metadata) differ. This patch reproduces the exact reviewed provider
  behavior from a clean checkout, not merely something resembling it.

No runtime world, native GameTest, or this repository's own fixture ran as part of this
verification — only the external provider's own build/test tasks, against a disposable scratch
copy of the pristine pin. The original `/tmp/dynamicdimensions-source` pin and
`/tmp/dynamicdimensions-composite` adaptation were read, never modified.

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
