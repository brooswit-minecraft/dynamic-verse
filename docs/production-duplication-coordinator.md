# Production duplication coordinator (source-only)

`ClosedDimensionDuplicationCoordinator` is the only new handoff between the
accepted close adapter and `ClosedDimensionCopier`. It accepts a fixed
`ClosedDimensionDuplicationIntent` plus one dependency-free representation of
the provider terminal. It permits a copier call only when the terminal has the
same source key and generation, `initiallyLoaded == true`,
`SAVED_AND_CLOSED`, and no mapped failure. Admission, removal callbacks, and
level disappearance are not terminals.

The coordinator centrally consumes that `(source key, generation)` before it
asks for storage paths. A duplicate terminal cannot reach the copier, including
when a previous attempt failed. It claims each destination key once. Mapping,
fence, and copier failures put that claimed destination in the unavailable
state; no retry, load, or publication method exists in this component. A
separate reviewed integration may act only on `COPIED` and must still restore
the original and load the destination with `loadDynamicDimension(...,
deleteData=false)` semantics.

## Required provider bridge, not implemented in this source-only unit

The pinned provider's public API is
`DynamicDimensionRegistry.beginQuiesceAndClose(ResourceLocation, PlayerRemover,
QuiesceAndCloseCompletion)`. Its `true` return is admission only. The
completion's `QuiesceAndCloseResult` has `key()`, `generation()`,
`initiallyLoaded()`, `status()`, `failureStage()`, and `cause()`; the bridge
must create `ProviderQuiesceCloseTerminal` from those exact fields and map only
the provider's `SAVED_AND_CLOSED`, `FAILED_SAVE`, and `FAILED_CLOSE` values.

The supported path method is
`LevelStorageSource.LevelStorageAccess#getDimensionPath(ResourceKey<Level>)`,
whose pinned source returns
`DimensionType.getStorageFolder(dimensionPath, this.levelDirectory.path())`.
The pinned `MinecraftServerMixin` calls it through its protected
`@Shadow LevelStorageAccess storageSource` for internal deletion only. Neither
`DynamicDimensionRegistry` nor `DynamicDimensionProvider` exposes that storage
access, a common-root resolver, or a source write/reload plus root/ancestor
stability lease. Therefore a runtime `DimensionStoragePathResolver` and
`SourceWriteReloadFence` cannot be honestly wired through the supported public
provider API at this pin. No private/mixin bypass or no-op fence is supplied.

The future bridge must retain its actual source write/reload and cooperative
root/ancestor stability exclusion until the copier closes its lease. It must
derive source and destination from the two supported `getDimensionPath` calls,
prove one common root and normalized relatives, then pass them to the
coordinator. Runtime copying, restoration, and destination loading remain
unexecuted and require separately serialized review.
