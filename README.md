# Dynamic Verse feasibility fixture

This is an isolated NeoForge 1.21.1 build/test fixture for MINECRAFT-470.
It is not a server deployment project and has no release or run-task hooks.

Pinned platform: Java 21, NeoForge 21.1.148, Gradle ModDev 2.0.146, and
Gradle 8.14. A full JDK (including `javac`) is required; a JRE is insufficient
for NeoForge's generated-Minecraft compilation.

Build the fixture with:

```sh
JAVA_HOME=/path/to/jdk-21 ./gradlew test --no-daemon
```

The first experiment must pin DynamicDimensions source or artifact evidence
before adding an integration. See `docs/feasibility-provenance.md`. Explicit-
seed creation, duplicate, and delete remain fail-closed until the
MINECRAFT-469 v5 capability requirements are proven.
