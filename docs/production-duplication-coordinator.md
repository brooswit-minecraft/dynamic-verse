# Production duplication coordinator

`ProviderDuplicationBridge` is the production handoff around the accepted
provider close adapter and `ClosedDimensionCopier`. It admits an operation only
for two explicitly `OwnedDimensionDescriptor` entries. Its one retained
`ClosedDimensionDuplicationCoordinator` accepts a copier call only for the
matching source key/generation, `initiallyLoaded == true`,
`SAVED_AND_CLOSED`, and no mapped failure. Admission, removal callbacks, and
level disappearance are not terminals.

The coordinator centrally consumes the terminal identity and claims each
destination once. Mapping, fence, and copier failures leave a claimed
destination unavailable; no retry or publication method
exists here. On successful copy the bridge restores only the original source.

## Implemented supported bridge

`MinecraftProviderDuplicationPort` calls public
`DynamicDimensionRegistry.beginQuiesceAndClose` and maps the completion's
`QuiesceAndCloseResult.key()`, `generation()`, `initiallyLoaded()`, `status()`,
`failureStage()`, and `cause()` exactly into `ProviderQuiesceCloseTerminal`.
It maps only `SAVED_AND_CLOSED`, `FAILED_SAVE`, and `FAILED_CLOSE`; either
failure field makes the terminal fail closed. After an exact successful copy it
uses public `DynamicDimensionRegistry.loadDynamicDimension(id, generator,
type, properties)` for normal original-source restoration. The pinned
implementation calls `createDynamicLevel(..., false)`, never its deleting
create path.

`MinecraftDimensionStoragePathResolver` uses public NeoForge 21.1.148
`MinecraftServer#getWorldPath(LevelResource.ROOT)` (ROOT is `.`) and public
`DimensionType#getStorageFolder(ownedKey, root)`. The latter maps an owned
custom key to `dimensions/<namespace>/<path>`, matching
`LevelStorageAccess#getDimensionPath`. The resolver rejects Overworld/root,
vanilla keys, unowned descriptors, escaped paths, and any mapping outside that
exact custom-dimension shape. It does not access `storageSource`, reflect into
the server, or bypass the registry.

## Shared operation/read gate

`VerseOperationGate` is the application-owned through-restoration exclusion.
The bridge enters it before provider admission and gives the matching terminal
generation a subordinate copier fence. Closing that copier sublease does not
release ready reads: the operation stays exclusive until the original normal
load succeeds and calls `restored()`. Any non-success, unsupported mapping,
fence/copy failure, or failed original load ends quarantined, so source and
destination remain unavailable. `GatedVerseAccessService` uses the same gate
and returns `PENDING` until its cooperating tick owner resolves the read.

This source/unit-only unit compiled against the reviewed `04341e14` provider
classes; the older local artifact is intentionally not replaced. Failed-close
restoration, initially-unloaded semantics, foreign descriptors, seed support,
and runtime provider/world execution remain held.
