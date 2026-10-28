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

The reviewed provider path has demonstrated one initially-loaded normal
close→confined-copy→source-restore→destination-load operation. See
`docs/feasibility-provenance.md`. Explicit-seed creation, arbitrary enrollment,
failed-close recovery, restart reconstruction, and delete remain fail-closed.
