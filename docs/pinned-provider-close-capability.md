# Pinned provider close-capability proposal (read-only)

This is a source audit and capability request, not an implementation or a
runtime experiment. It applies to DynamicDimensions at
`ecfbdf694d4f4c103404a77858d00c54a91b8020`, resolved here against NeoForge
`21.1.148` sources. The resolved source archive is
`/tmp/dynamicdimensions-composite/neoforge/build/moddev/artifacts/neoforge-21.1.148-sources.jar`.

## Concrete ownership and close ordering

| Owner/source location | Owned resource(s) | close order / skipped path |
| --- | --- | --- |
| `net.minecraft.server.level.ServerLevel:239-264,1692-1695` | entity `SimpleRegionStorage` through `EntityStorage`/`PersistentEntitySectionManager`; `ServerChunkCache` | `ServerLevel.close()` first calls `Level.close()` (the chunk source), then `entityManager.close()`. Any `Level.close()` exception skips the entity manager. |
| `net.minecraft.world.level.Level:883-885` | `ServerLevel` chunk source | `Level.close()` delegates to `getChunkSource().close()` with no aggregate/finally protection. |
| `net.minecraft.server.level.ServerChunkCache:75-108,308-312` | save pipeline, light engine, `ChunkMap` (including chunk worker and POI storage) | Sequential `save(true)`, `lightEngine.close()`, `chunkMap.close()`. A save failure skips light engine and map; a light-engine failure skips map. |
| `net.minecraft.server.level.ChunkMap:101,190-215,431-437` | `queueSorter`, `PoiManager`, inherited `ChunkStorage.worker` | `queueSorter.close()` then `poiManager.close()` run in `try`; inherited worker close runs in `finally`. Queue-sorter failure skips POI but reaches worker; POI failure reaches worker. |
| `net.minecraft.world.entity.ai.village.poi.PoiManager:41,57,238-240` | POI `SimpleRegionStorage` | Inherits `SectionStorage.close()`, which closes its storage. It is skipped when queue-sorter close throws. |
| `net.minecraft.world.level.chunk.storage.ChunkStorage:25,125-127` | chunk region-file worker | Reached only through `ChunkMap`'s `finally`; it independently may throw. |
| `net.minecraft.world.level.chunk.storage.EntityStorage:26,136-138` | entity `SimpleRegionStorage` | Reached only after the entire chunk-source close returns successfully. |
| `net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess:411,646-648` | level/session lock | Owns the lock, but `ServerLevel.close()` does not close this shared server access. It cannot prove the dimension's chunk/entity/POI writers are gone. |

The locations above identify direct source methods and constructor ownership,
not a claim that they enumerate every transitive writer. They establish enough
skipped-close paths to reject recovery: no close-complete state, all-resource
completion report, writer ownership lease, or reopen API is exposed by this
path.

## Capability verdict and minimum provider contract

**Pinned-provider verdict: unsupported.** There is no supported operation that
can prove that a `ServerLevel.close()` exception released every writer for a
dimension. The M470 adapter must therefore retain `QUARANTINED`, never create a
replacement at the same path, and never report the admitted instance `READY`.

For a future provider version to make recovery reviewable, it would need an
explicit, server-thread-only close capability with all of these properties:

1. Own the complete close graph at the provider/runtime boundary, rather than
   inferring it from a thrown `ServerLevel.close()`.
2. Attempt every owned close in a documented order, retaining every thrown
   failure in an immutable `CloseReport` keyed by level identity and
   generation.
3. Publish `ALL_WRITERS_RELEASED` only when every owned writer/worker and its
   pending work is conclusively closed; otherwise publish `PARTIAL_CLOSE` and
   permanently fence that storage path from replacement construction.
4. Make the report single-use and idempotent: a repeated close/recovery request
   returns the same generation/report, never invokes old closers again.
5. Provide no reopen claim for the old `ServerLevel`; replacement admission
   must require the successful all-writers report and a separate registry/client
   transaction review.

Until such a provider-owned contract exists, a Mixin-only aggregate wrapper is
not support: it cannot reach private/transitive writer state or distinguish a
completed close from a close which threw after an unknown partial release.

## Failure matrix

| Failure point | Directly known released set | Directly known uncertain/skipped set | Required current outcome |
| --- | --- | --- | --- |
| `ServerChunkCache.save(true)` throws | none proven | light engine, map/POI/chunk worker, entity store | quarantine; no replacement |
| light-engine close throws | save returned | map/POI/chunk worker, entity store | quarantine; no replacement |
| queue-sorter close throws | chunk-source close reached map | POI and entity store; worker may be attempted by `finally` but completion is not proven | quarantine; no replacement |
| POI close throws | queue sorter returned | chunk worker may be attempted by `finally`; entity store skipped | quarantine; no replacement |
| chunk-worker close throws | prior map substeps may have returned | entity store skipped | quarantine; no replacement |
| entity store close throws | chunk source returned | entity writer's own final state unknown | quarantine; no replacement |
| any future replacement/fresh close throws | no all-writers proof | new generation's writer state uncertain | quarantine that generation; do not claim release or retry it |

No recovery, provider fault injection, source-data copy/delete, or server run
is authorized by this document. Explicit per-verse seed remains
`ERR_PER_VERSE_SEED_UNSUPPORTED`.
