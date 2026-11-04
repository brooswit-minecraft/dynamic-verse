# Closed-dimension copy seam

Main-source implementation: `io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionCopier`.
It is the sole duplication component introduced for the confined normal-success
path. It has no world, command, GameTest, or recovery implementation.

## Production caller boundary

The future provider-facing caller must map the accepted adapter's actual
`QuiesceAndCloseResult` into `ClosedDimensionReceipt` only after checking:

```java
result.key().equals(admittedKey)
&& result.generation() == admittedGeneration
&& result.initiallyLoaded()
&& result.status() == SAVED_AND_CLOSED
&& result.failureStage() == null
&& result.cause() == null
```

It then calls:

```java
copier.copy(
    new ClosedDimensionCopyRequest(storageRoot, sourceRelative, destinationRelative,
        admittedKey.location().toString(), admittedGeneration),
    receipt,
    sourceWriteReloadFence);
```

`SourceWriteReloadFence.retain(key, generation)` is acquired before any storage
operation and released only after the copy returns or fails. Its production
implementation must retain the source write/reload and provider-storage-mapping
fence across the whole call. The accepted adapter at
`04341e1404c9a07bdc1f2171c391f81837b86972` is the receipt producer; it does
not yet provide this caller/fence, so no runtime is authorized to invoke the
seam until the bridge is separately reviewed.

The caller resolves `storageRoot`, `sourceRelative`, and `destinationRelative`
from the pinned provider's supported
`LevelStorageAccess.getDimensionPath(ResourceKey<Level>)` mapping. It must
derive a single common storage root and normalized relatives from those two
returned paths; command strings and arbitrary server-root paths are forbidden.
If no same-root mapping/fence can be established, it must not call the copier.

## Filesystem contract

The copier requires normalized relative paths below an existing non-linked
storage root; rejects overlapping source/destination paths; requires a source
directory; and atomically claims the destination only with `createDirectory`
when it did not already exist. It never replaces an entry. It rejects linked
roots/ancestors/entries and non-regular non-directory entries. The source is
opened through `SecureDirectoryStream` using `NOFOLLOW_LINKS`; file I/O uses
descriptor-relative source reads and destination `CREATE_NEW` writes. A
provider lacking `SecureDirectoryStream` is refused before copying.

The JDK has no descriptor-relative directory-creation operation. Consequently
the regular `createDirectory` steps are safe only while the caller-held
provider-storage-mapping fence prevents a cooperative provider/runtime from
substituting the resolved root or destination parent. This seam makes no claim
to defend against an external hostile process that can rename the server's
world directory outside that fence. If the assigned runtime cannot provide
that storage-root stability guarantee, the supported operation is **refusal**;
it must not substitute an unsafe walker or a native/registry bypass.

Any exception leaves the claimed partial destination present and unavailable:
the component does not delete, load, publish success, or retry it. The receipt
generation is consumed before the fenced operation, so an uncertain copy cannot
be retried automatically.

## Unit receipt

`ClosedDimensionCopierTest` uses only JUnit temporary paths and the main-source
component. It covers nested exact-directory copy/source immutability, existing
destination, overlap/traversal, linked root/ancestor/entry, a FIFO special
file, injected partial failure, and absent/wrong/failed/duplicate receipts.
No provider/world path is exercised by the test.
