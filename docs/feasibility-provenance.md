# Feasibility provenance

This fixture is isolated test/build infrastructure for MINECRAFT-470. It does
not configure or connect to a Minecraft server.

## Platform

- Minecraft: `1.21.1`
- NeoForge: `21.1.148`
- NeoForge Gradle ModDev plugin: `2.0.146`
- Java: `21`
- Gradle wrapper: `8.14`

The NeoForge coordinate was checked against the official NeoForged Maven
metadata and POM on 2026-10-27. The pinned version matches the current
DynamicDimensions `minecraft/1.21` source declaration.

## DynamicDimensions

The experiment source pin is:

```
TeamGalacticraft/DynamicDimensions
branch: minecraft/1.21
commit: ecfbdf694d4f4c103404a77858d00c54a91b8020
declared mod version: 0.10.0
```

At this pin, the upstream Gradle publication configuration only supplies a
Maven repository when `NEXUS_REPOSITORY_URL` is present. Consequently this
fixture does not claim a public consumable artifact coordinate; the experiment
must use this source pin or later record a verified artifact repository and
coordinate.

### Source-route build result

On 2026-10-27, the pinned checkout was invoked with:

```sh
JAVA_HOME=/path/to/jdk-21 gradle :neoforge:build --no-daemon
```

It failed before compiling DynamicDimensions because Gradle could not resolve
the upstream plugin `dev.galacticraft.mojarn:dev.galacticraft.mojarn.gradle.plugin:0.6.1+19` from the Gradle Plugin Portal, Maven Central, Galacticraft's
declared Maven repository, Fabric Maven, or NeoForged Maven. This is a source
route dependency/publication hold, not evidence that the library provides or
lacks independently seeded levels or verified save-and-close completion.

### Local composite-build candidate

The previous source-route hold has a reproducible, **local-only** candidate
route. On 2026-10-27, a separate disposable checkout of the DynamicDimensions
pin was configured with this addition to `pluginManagement`:

```kotlin
includeBuild("/tmp/mojarn-source")
```

`/tmp/mojarn-source` was an official TeamGalacticraft/mojarn checkout pinned
to `beb2addf7ec99a398b6a243cdcec73f4f33f6b01`. That source declares plugin ID
`dev.galacticraft.mojarn` and version `0.6.1`; its local build suffix is `+0`.
It is **not** claimed equivalent to the unresolved historical `0.6.1+19`
publication.

With a full JDK 21, the disposable composite command below completed
successfully (14 tasks, 10m25s):

```sh
JAVA_HOME=/path/to/jdk-21 gradle :neoforge:build --no-daemon
```

Nothing was published or installed to a global Maven cache, and the original
DynamicDimensions checkout was not modified. This establishes only a supported
Gradle included-build source route for a future isolated integration test. It
does not prove independent NORMAL/SUPERFLAT seeds, forced save-and-close
completion, copy safety, or source restoration.

## Explicit-seed falsification

The composite produced the disposable NeoForge library artifact
`dynamicdimensions-neoforge-0.10.0+ecfbdf69-dirty.jar` with SHA-256
`b25aefb589ae1c8635679373230d536c6c77bd21942f65d0f9765436e5046d79`.
`verifyLocalDynamicDimensionsArtifact` verifies this exact jar when placed at
`local-artifacts/dynamicdimensions-neoforge.jar`; that ignored directory is
never published and the artifact is not installed into a Maven cache.

The pinned implementation decisively falsifies explicit per-verse seeds: both
`createDynamicDimension` overloads accept only an identifier,
`ChunkGenerator`, `DimensionType`, and optional properties. Its sole
`ServerLevel` construction in `DynamicDimensionRegistryImpl#createDynamicLevel`
passes `BiomeManager.obfuscateSeed(worldData.worldGenOptions().seed())`.
Thus a requested seed cannot reach the constructed level's seed through this
API; NORMAL and SUPERFLAT receive the save-wide seed. Supplied seed requests
must remain fail-closed before filesystem/registry mutation
(`ERR_PER_VERSE_SEED_UNSUPPORTED`), never substituted with the server seed.

## Queued removal probe

With no existing runner active, the pinned composite's isolated GameTest runner
was executed on 2026-10-27:

```sh
JAVA_HOME=/tmp/temurin21 /tmp/gradle-8.14/bin/gradle :neoforge:runGameTest --no-daemon
```

The disposable run directory was
`/tmp/dynamicdimensions-composite/neoforge/run`. It started a `GameTestServer`,
ran six real DynamicDimensions tests, and exited `BUILD SUCCESSFUL` in 44
seconds. The suite exercises create, deferred unload, delete, reload, and
file-preservation paths; the terminal runner output reported all six required
tests passed. This is evidence that the queued removal path can complete a
normal save-and-close in this isolated environment.

It is not a verified failure protocol. The pinned `MinecraftServerMixin`
executes `level.save(null, true, level.noSave)` in a try-with-resources block,
catches `IOException`, and then removes the level from the map in `finally`.
The public `unloadDynamicDimension` boolean is therefore admission evidence,
not an explicit terminal save/close result. No safe failure injection or
source-state restoration hook is exposed by this provider; a snapshot copy or
delete must remain prohibited until a provider adaptation supplies explicit
success/failure completion and restoration semantics.

## Current capability boundary

MINECRAFT-469 v5 remains authoritative: explicit independently seeded creation
and a verified forced-save-and-close result are unproven. No lifecycle command
is registered here, and no fail-open fallback is permitted. Future test doubles
may verify the nonblocking operation/lease state machine, but cannot establish
the real DynamicDimensions capabilities.
