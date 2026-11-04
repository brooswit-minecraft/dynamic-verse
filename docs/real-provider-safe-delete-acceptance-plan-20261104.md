# Real-provider safe-delete acceptance: fixture plan (for review, not yet executed)

Per MINECRAFT-470 review (PR #3, https://github.com/brooswit-minecraft/dynamic-verse/pull/3#issuecomment-6633684354):
`runtimeConfirmedDeleteRemovesOnlyItsOwnMetadataAndInterruptedDeleteStaysQuarantinedAfterRestart`
is useful production-wiring evidence, but its state store is
`InMemoryManagedVerseStateStore`, its `DeleteGateway` port is a controlled fake
(`matchesCurrent`/`beginClose`/`isClosedAndAbsent` all hand-set), and
reconstruction happens in the same process. It does not prove actual provider
close/delete, nor a disk-backed process restart. This document proposes the
bounded native fixture that would, **for review before any execution** — no
native run, no destructive action, happens from this document alone.

## Scope: what this fixture would add, and would not

Adds: one native GameTest exercising `ServerVerseRuntime.beginConfirmedDelete`
through `ServerVerseRuntime.DeleteGateway.minecraft(server)` (the real
production factory, not a fake `Port`) against the reviewed provider's real
`beginQuiesceAndClose`, on a freshly created, disposable, test-owned world —
never a retained or user world.

Does not add: a new worker, a live/user-world delete, an unbounded matrix of
every admission branch natively (those stay covered at the unit/controlled
level, as already exist — `SafeDeleteOperationTest`,
`ConfinedDimensionDeleterTest`, and the existing controlled runtime test).
This fixture targets exactly the five properties below and nothing broader.

## The five properties to prove natively

1. **Confirmed target only.** The bare `/verse delete <name>` (or
   `beginDelete`) path never reaches the provider or the filesystem, even for
   an eligible world; only `beginConfirmedDelete` (the `confirm` command) does.
   Already covered at the dispatcher level (`lifecycleDuplicateAndListBoundary`);
   this fixture's native run should assert bare delete leaves the real created
   world fully intact (loaded, saved, files present) before confirming it.
2. **Occupied / refused / failed-close ⇒ no removal.** Reproduce each
   admission-rejecting path through the real provider: a player present in
   the target world, and a provider close that fails (`FAILED_SAVE`/
   `FAILED_CLOSE`, or an exception from the real `beginQuiesceAndClose`).
   Assert in each case: the world is still loaded and on disk afterward, the
   durable intent is released or quarantined per the already-reviewed
   REJECTED_NO_EFFECT/REJECTED_UNCERTAIN-equivalent distinction for delete,
   and no directory under `dimensions/dynamicverse/` was touched.
3. **Exact successful close then confined removal.** A real admitted close
   that the provider actually completes (`SAVED_AND_CLOSED`) should then
   drive the real `ConfinedDimensionDeleter` against the world's real,
   on-disk storage directory (under the test's own disposable
   `run/<fixture>/world` root, never `local-artifacts/` or a shared path).
   Assert the directory is actually gone, the catalog/provenance/durable
   intent are gone, and the terminal fires exactly once as `DELETED`.
4. **Unrelated-world preservation.** A second, untouched owned world created
   in the same disposable server must still be loaded, on disk, and READY
   after the first world's confirmed delete — proving the real removal path
   is as narrowly confined as the controlled test already showed in-memory.
5. **Disk-backed interrupted-DELETE recovery, no automatic deletion/reload.**
   Admit a confirmed delete, then terminate the JVM (or otherwise simulate a
   crash) before the provider's close terminal arrives — not just "never call
   the completion" in-process, but an actual process exit with the durable
   intent already flushed to its real state file. A second, fresh server
   process reading that same real state file must quarantine the interrupted
   world (never auto-delete it, never silently reload it as READY), mirroring
   property 5 of the already-merged controlled test, but across a real
   process boundary and a real state file instead of a shared in-memory
   object.

## What this plan deliberately does not resolve yet

- The exact mechanism for forcing a provider-side close failure
  deterministically (fault injection into the reviewed provider, or a
  reproducible real-world condition) is not chosen here — needs a decision
  before property 2 can be implemented, to avoid an unreliable or flaky
  native test.
- The exact mechanism for "terminate the JVM mid-operation" for property 5
  (kill the native GameTestServer process at a known point, vs. a second
  cooperating process) is likewise not chosen here.
- Whether this becomes one combined fixture or multiple small ones is left
  to implementation time; the five properties above are the acceptance bar
  either way.

## Explicitly not authorized by this document

No native run, no world creation, no filesystem mutation, no provider
invocation. This is a plan only, returned for review before any destructive
native validation, per the no-live-world-deletion gate already in force on
this ticket.
