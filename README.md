# Dynamic Verse feasibility fixture

This is an isolated NeoForge 1.21.1 build/test fixture for MINECRAFT-470.
It is not a server deployment project and has no release or run-task hooks.

Pinned platform: Java 21, NeoForge 21.1.148, Gradle ModDev 2.0.146, and
Gradle 8.14. A full JDK (including `javac`) is required; a JRE is insufficient
for NeoForge's generated-Minecraft compilation.

## Provider setup (required before any build)

`build.gradle` compiles main and test sources against the reviewed
DynamicDimensions provider's classes, not only the native GameTest profiles.
Both the ordinary `compileJava`/`compileTestJava`/`test` tasks and the native
provider profiles need this input; there is no build mode that skips it.

- Source pin: `TeamGalacticraft/DynamicDimensions`, branch `minecraft/1.21`,
  commit `ecfbdf694d4f4c103404a77858d00c54a91b8020`. See
  `docs/feasibility-provenance.md` for how this pin was verified.
- The actually-reviewed classes come from a **local-only** adaptation of that
  pin (`docs/local-provider-quiesce-close-adaptation.md`), on branch
  `minecraft-470-local-quiesce-close` of a disposable composite checkout, at
  commit `4b09389`, *plus* further uncommitted explicit-seed changes on top.
  That tree itself has no clean, replayable commit.
- **Reproducible alternative (verified, prefer this on a fresh machine):**
  `provider-patches/dynamicdimensions-reviewed-seed-close.patch` applies
  cleanly to the pristine pinned commit above and reproduces that same
  adaptation from source, with one admission-order defect found and fixed
  during review (see `docs/provider-source-reproducibility.md`) — so its
  build is no longer byte-identical to the pinned candidate jar below, only
  class-by-class equivalent apart from that fix and its own test. See
  `docs/provider-source-reproducibility.md` for the exact recipe, the fix,
  and the full class-comparison manifest.
- Pinned candidate jar checksum (the current default for
  `acceptedDynamicDimensionsJar`, predating the admission-order fix above):
  SHA-256 `50b7b75a0751f9774a0586926a9e441512fb62fc815f992052aed9647fc3b30e`.
  `reviewedDynamicDimensionsClasses` must point at the compiled-classes output
  of that exact same tree (e.g. `.../build/classes/java/main` from building
  the jar above); do not substitute an arbitrary released provider build. A
  build from the corrected patch is preferable where available — see the
  reproducibility doc for its own (different) jar checksum.

Build the fixture with:

```sh
# First run once with network access so Gradle populates its dependency and
# toolchain caches; --offline fails on a cold cache.
JAVA_HOME=/path/to/jdk-21 ./gradlew compileJava compileTestJava test --no-daemon \
  -PreviewedDynamicDimensionsClasses=/path/to/reviewed/classes

# Later runs may stay offline once those caches are warm.
JAVA_HOME=/path/to/jdk-21 ./gradlew compileJava compileTestJava test --offline --no-daemon \
  -PreviewedDynamicDimensionsClasses=/path/to/reviewed/classes
```

Native provider GameTest profiles additionally need `acceptedDynamicDimensionsJar`;
see `docs/continuation-20261102.md` for that command and for pinned
`local-artifacts/dynamicdimensions-neoforge.jar` setup.

The reviewed provider path has demonstrated one initially-loaded normal
close→confined-copy→source-restore→destination-load operation. See
`docs/feasibility-provenance.md`. Explicit-seed creation, arbitrary enrollment,
failed-close recovery, restart reconstruction, and delete remain fail-closed.
