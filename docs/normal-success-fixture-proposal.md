# M470 confined normal-success fixture proposal (source-only)

## Scope and identities

This proposal is deliberately narrower than source restoration after a failed
close. It is for one **initially loaded, test-owned, disposable** source and
only proceeds after the accepted adapter's exact terminal success. It does not
authorize a server run, copy, create/load/unload execution, fault injection,
deletion of existing data, recovery, registry bypass, or production changes.

| Artifact | exact identity | role |
| --- | --- | --- |
| pinned provider baseline | `ecfbdf694d4f4c103404a77858d00c54a91b8020` | DynamicDimensions source/API baseline |
| accepted local adapter | `04341e1404c9a07bdc1f2171c391f81837b86972` | production-used `beginQuiesceAndClose` terminal policy; review 50246 |
| existing normal lifecycle evidence | receipt 50137 / the six existing provider GameTests | unchanged precedent only; this proposal does not repeat them |
| source fixture ID | runtime-assigned `dynamicverse:m470_success_source_<runNonce>` | must be unused in server registries and have no existing dimension path |
| destination fixture ID | runtime-assigned `dynamicverse:m470_success_destination_<runNonce>` | distinct, unused, and no existing dimension path |

The serializing runtime owner must generate the nonce, verify both IDs and
paths are absent **before** any creation, and stop if either is occupied. The
fixture owns only those two IDs and its isolated run directory. It must not
clean an occupied path to make the precondition true.

## Exact supported provider operations

The adapter exposes this public call:

```java
boolean DynamicDimensionRegistry.beginQuiesceAndClose(
    ResourceLocation id,
    @Nullable PlayerRemover remover,
    QuiesceAndCloseCompletion completion)
```

`true` is admission only. The callback is:

```java
void QuiesceAndCloseCompletion.complete(QuiesceAndCloseResult result)
```

where `QuiesceAndCloseResult` contains `key`, `generation`,
`initiallyLoaded`, `sourceStillLoaded`, `sourceReadiness`, `status`,
`failureStage`, and `cause`. This proposal accepts the source operation only
when exactly one callback matches the admitted source key and its captured
generation, has `initiallyLoaded == true`, `status == SAVED_AND_CLOSED`, and
no failure stage/cause. Admission, `DimensionRemovedCallback`, a map
disappearance, or the legacy `unloadDynamicDimension` boolean are not success
evidence.

The non-destructive reload signature is:

```java
@Nullable ServerLevel DynamicDimensionRegistry.loadDynamicDimension(
    ResourceLocation id, ChunkGenerator generator, DimensionType type,
    DynamicDimensionProperties properties)
```

`DynamicDimensionRegistryImpl.loadDynamicDimension` calls its internal create
path with `deleteData == false`. By contrast both
`createDynamicDimension(...)` overloads pass `deleteData == true` to the same
path. The fixture must never call `createDynamicDimension` for either an
existing source or copied destination path.

## Fixture construction and descriptor ownership

Before creation, the fixture constructs and retains its own descriptor:

- a known test-owned `ChunkGenerator` (not an inferred foreign-world
  generator);
- an immutable value snapshot of the five public
  `DynamicDimensionProperties` getters—`priority`, `baseGravity`,
  `basePressure`, `universalDrag`, and `magneticNorth`—with defensive
  `Vector3f` copies; and
- a serializable `DimensionType` descriptor.

The descriptor must recreate a **fresh equivalent** `DimensionType` for every
provider registration. `DynamicDimensionRegistryImpl.createDynamicLevel`
rejects a type when `this.dimTypes.stream().anyMatch(t -> t == type)`; reusing
an already registered object is therefore unsupported. A reviewable fixture
uses the supported `DimensionType.DIRECT_CODEC` with `RegistryOps`/`NbtOps` to
encode the test-owned type before close and decode a fresh object for each
source/destination `loadDynamicDimension` call. The provider remains the only
registrant through its normal `RegistryUtil.registerUnfreeze` path—there is no
direct registry mutation.

The proposal intentionally does not claim a generic public descriptor for an
arbitrary pre-existing level's generator/properties. `DynamicDimensionProperties`
is abstract and offers no copy factory, while the public provider API offers
no original `LevelStem` descriptor. Restricting this fixture to a test-owned
generator/properties descriptor is the smallest supported way to preserve
those values without guessing or bypassing registries.

## Serialized normal-success procedure for later review

1. Create the fresh, absent source from the test-owned descriptor and wait for
   normal registration. Assert `server.getLevel(sourceKey)` identity,
   `dynamicDimensionExists(sourceKey)`, `anyDimensionExists(sourceId)`, and
   the stored property snapshot. Write a unique persistent marker block/state
   at a fixed source position.
2. Invoke the accepted `beginQuiesceAndClose` once. Await its matching terminal
   callback across ticks; do not use a legacy callback, queued admission, or
   arbitrary timeout as proof. Any non-success, duplicate, missing, mismatched
   generation/key, or uncertain source state stops the fixture and forbids all
   dependent copy/load work.
3. Only after independent review of the exact source/destination identities
   and a separately assigned serialized runtime, invoke the **already-reviewed
   confined copy path** from the source dimension directory to the distinct,
   proven-fresh destination directory. It must preserve a file-level evidence
   receipt and reject existing destination data. This workspace contains no
   such copy implementation or review identifier: a search of Dynamic Verse
   and the pinned provider finds deletion (`FileUtils.deleteDirectory`) but no
   confined-copy API. Its exact implementation path, commit, and review are
   therefore mandatory prerequisites, not implied by this proposal.
4. Load the source with `loadDynamicDimension(sourceId, fixtureGenerator,
   freshSourceType, propertySnapshot)`—never `createDynamicDimension`—and wait
   for registration. Verify the source key/level registration and readiness,
   original generator descriptor/properties, and its persistent marker.
5. Independently load the distinct destination with its own freshly decoded
   equivalent `DimensionType` and the same test-owned generator/properties.
   Verify destination registration and copied marker. Its distinct key and
   directory must be asserted before any mutation. A later, separately
   terminalized destination-only marker mutation may establish persistence
   independence only if it has its own `SAVED_AND_CLOSED` receipt; until then,
   path/key separation plus copied-marker visibility is the only claim.

The source may be restored before destination loading; all provider actions
remain serial. The fixture must not concurrently load two instances for one
ID, retry an uncertain close, clear quarantine, or infer readiness from a
callback other than the accepted terminal result.

## Review gates and held cases

| prerequisite | owner / evidence required |
| --- | --- |
| exact isolated runtime and exclusive serialization | assigned runtime owner; no server while 453 owns exclusive validation and 478 performs independent source work |
| exact confined-copy source/review receipt | owner of the existing copy path; destination freshness and no-link/independence guarantees |
| fixture ID/path absence and marker descriptor | runtime owner, recorded before creation |
| accepted terminal result | matching key/generation `SAVED_AND_CLOSED` callback receipt |
| source reload assertions | source registration/readiness + original marker + generator/type/properties descriptor evidence |
| destination assertions | separate registration/key/path + copied marker, with any mutation requiring its own terminal receipt |

Any save, close, copy, reload, registration, descriptor-decode, or marker
assertion failure stops the dependent sequence and retains its evidence. This
does not relax the held failed-close restoration, partial-close recovery,
initially-unloaded semantics, copy/delete generally, or
`ERR_PER_VERSE_SEED_UNSUPPORTED`.
