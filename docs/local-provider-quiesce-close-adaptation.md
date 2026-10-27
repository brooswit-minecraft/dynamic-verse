# Local provider `beginQuiesceAndClose` adaptation

This is a **local-only review artifact**, not an upstream fork or published
dependency. It was prepared from the pinned DynamicDimensions source commit
`ecfbdf694d4f4c103404a77858d00c54a91b8020` in the disposable composite at
`/tmp/dynamicdimensions-composite`. The adapter commit is
`f6260fd592cdd4b8767d3bb7aa1c60920423cd52` on local branch
`minecraft-470-local-quiesce-close`. The original pinned checkout at
`/tmp/dynamicdimensions-source` remains unchanged at the same source commit.

Review the adaptation without any external publication with:

```bash
git -C /tmp/dynamicdimensions-composite diff \
  ecfbdf694d4f4c103404a77858d00c54a91b8020 \
  f6260fd592cdd4b8767d3bb7aa1c60920423cd52 -- common
```

## Call graph and terminal origin

```text
DynamicDimensionRegistry.beginQuiesceAndClose(id, remover, completion)
  -> DynamicDimensionRegistryImpl.beginQuiesceAndClose(...)
     -> DynamicDimensionProvider.dynamicdimensions$beginQuiesceAndClose(...)
        -> MinecraftServerMixin: queue QuiesceAndCloseTicket
           (key, monotonically assigned generation, initiallyLoaded, callback)
        -> tickChildren HEAD drains the queue
           -> quiesceAndClose(ticket)
              -> relocate players
              -> ServerLevel.save(null, true, level.noSave)
              -> ServerLevel.close()
              -> unregister/remove/send client notifications
              -> completion.complete(SAVED_AND_CLOSED)
```

The terminal callback is not `DimensionRemovedCallback`: that legacy callback
remains a pre-save notification in the original removal path. The local seam
reports success only after the forced save, close, and lifecycle cleanup above.
It reports `FAILED_SAVE` if player relocation or `save` throws, and
`FAILED_CLOSE` if `close` throws or the source is no longer loaded when the
queued work executes. These outcomes retain the source's key, operation
generation, original loaded state, current loaded state, failure stage, and
cause. Failure paths do not remove the level, unregister registries, remove the
dynamic registry key, send removal packets, delete data, or invoke the legacy
removed callback.

At most one operation per source key is admitted while it is queued. A rejected
admission returns `false`; every accepted operation has exactly one terminal
callback. The state machine permits `SAVED_AND_CLOSED` only after player
relocation, save, and close, so queued admission or disappearance is never
treated as proof of success.

## Validation performed

The following only compiled the disposable local composite and ran the three
new pure ordering/state-machine tests. It did not run fault injection, copy
data, publish an artifact, or open a staging/live world:

```bash
JAVA_HOME=/tmp/temurin21 PATH=/tmp/temurin21/bin:$PATH \
  /tmp/gradle-8.14/bin/gradle :common:test :neoforge:compileJava --no-daemon
```

Result: `BUILD SUCCESSFUL in 45s`; the test class proves that success requires
save before close, save failure cannot be emitted after close, and a terminal
result cannot be emitted twice.

## Explicit deferred test seam

The production method `MinecraftServerMixin.quiesceAndClose` is the sole
real-provider seam for the next disposable GameTest. A fault injector has not
been added and no private-operation instrumentation has been used. After
independent review, a test-only adapter at the `level.save` and `level.close`
calls may force the two exceptions; expected failure assertions are one
terminal result, original key still registered, no removal packet, and loaded
state preserved for an initially loaded source. An initially unloaded source
must remain unloaded and report `FAILED_CLOSE/SOURCE_NOT_LOADED` rather than
being recreated implicitly.

Copy/delete remains prohibited unless the completion status is
`SAVED_AND_CLOSED`. The separate explicit-seed capability result remains
`ERR_PER_VERSE_SEED_UNSUPPORTED`.
