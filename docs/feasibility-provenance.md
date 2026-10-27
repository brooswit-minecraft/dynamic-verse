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

## Current capability boundary

MINECRAFT-469 v5 remains authoritative: explicit independently seeded creation
and a verified forced-save-and-close result are unproven. No lifecycle command
is registered here, and no fail-open fallback is permitted. Future test doubles
may verify the nonblocking operation/lease state machine, but cannot establish
the real DynamicDimensions capabilities.
