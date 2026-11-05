# Real-provider safe-delete acceptance: fixture/runner (for review, not yet executed)

Per MINECRAFT-470 review: `runtimeConfirmedDeleteRemovesOnlyItsOwnMetadataAndInterruptedDeleteStaysQuarantinedAfterRestart`
is useful production-wiring evidence, but its state store is
`InMemoryManagedVerseStateStore`, its `DeleteGateway` port is a controlled fake,
and reconstruction happens in the same process. Per the follow-up technical
plan review (https://github.com/brooswit-minecraft/dynamic-verse/pull/3,
"latest technical plan review" comment), this document was updated from a
plan into the actual narrow source/runner it specifies, for review **before
any native execution**. Nothing in this document, or in the source it
describes, has been run.

## What exists now (compiled, not executed)

- `src/main/java/.../lifecycle/ProviderSafeDeleteCrashFixture.java`: the
  two-JVM Phase A/B fixture (property 5, below).
- `dev.galacticraft.dynamicdimensions.impl.internal.TestOnlySaveFailureHook`,
  wired into `ServerLevelSaveOperations.save`, added to
  `provider-patches/dynamicdimensions-reviewed-seed-close.patch` (PR #2,
  `e1a98b0`): the deterministic `FAILED_SAVE` injection for property 2.
- `build.gradle`'s `providerSafeDeleteCrashPhaseA`/`PhaseB` run registrations
  and `DynamicVerse`'s `dynamicverse.providerSafeDeleteCrashPhase` selector.
- `scripts/runProviderSafeDeleteCrashWithReceipt.sh`: orchestrates both
  phases, including the PID-verified forced kill described under property 5.

Not yet built: a dedicated in-process success/occupied-refusal/busy-refusal/
FAILED_SAVE native fixture (property 1's "separate deterministic
success/refusal/failure scenarios" from the restart scenario). The
restart/crash half (property 5) was prioritized first since it is the more
novel, higher-risk mechanism; the in-process scenarios are comparatively
close to the existing patterns already reviewed elsewhere in this ticket
(`ProviderBridgeIntegrationGameTest`, `ProviderOrdinaryRestartFixture`) and
are proposed as the next increment rather than built speculatively here.

## 1. Separate the scenarios (done at the fixture-design level)

`ProviderSafeDeleteCrashFixture` is dedicated solely to the two-process
restart/crash scenario and uses only real production wiring: the actual
`ServerVerseRuntime.create(server)` factory, the actual
`ServerVerseRuntime.DeleteGateway.minecraft(server)`, the corrected provider,
and the actual `ManagedVerseStateFileStore.minecraft(server)`. No fake
successful receipt, no fake absence check, no in-memory persistence appears
anywhere in this class. The deterministic success/refusal/failure scenarios
are intentionally left for a separate fixture (not yet written — see above).

## 2. Failure injection

Existing provider test hooks were inspected first (`QuiesceAndCloseBridgeTest`
and friends): they exercise the internal step classes directly with fake
sources, not the real `MinecraftServerMixin`/`ServerLevel` path, so they do
not suffice for a native failure. Added `TestOnlySaveFailureHook`
(`common/src/main/java/dev/galacticraft/dynamicdimensions/impl/internal/`,
in `provider-patches/`), called from `ServerLevelSaveOperations.save`
immediately before the real `ServerLevel.save(...)`:

- Inert unless the exact system property `dynamicdimensions.testOnlyFailSaveForKey`
  is set to the target's exact dimension location; fires at most once per JVM
  (a `consumed` latch); throws a distinct `TestOnlySaveFailureHook.TestOnlySaveFailure`
  type so a real production save failure is never masked as this deliberate one.
- Read `QuiesceAndCloseOrchestrator.execute` directly to confirm the real
  orchestrator already catches whatever `RuntimeException` the save step
  returns and maps it to `FAILED_SAVE` without calling `quarantine()` at that
  stage — this hook rides that existing, already-reviewed path; it adds no
  new failure path of its own.
- Occupied refusal and known-no-effect busy refusal need no injection: an
  actual test-owned player placed in the target world, and an actual held
  read lease, exercise the real `hasPlayers`/gate-busy checks directly.

Full source/target/hook diff: `provider-patches/dynamicdimensions-reviewed-seed-close.patch`
(PR #2, commit `e1a98b0`); see `docs/provider-source-reproducibility.md` there
for the class-comparison manifest and verification already performed on that
patch (compiles, provider's own 18 unit tests unchanged, new jar hash
recorded). Not yet exercised through the new hook natively.

## 3. Crash proof — `ProviderSafeDeleteCrashFixture` + the wrapper script

- **Barrier placement, justified by actual queue ordering, not assumed
  timing:** `dynamicdimensions$beginQuiesceAndClose` only calls
  `pendingQuiesceAndCloses.admit(...)` — admission is enqueue-only. The real
  close only runs when `MinecraftServerMixin`'s `tickServer` injection drains
  that queue. Phase A's `started()` runs from `ServerStartedEvent`, before
  the server has ever reached its tick loop; if that call never returns, no
  tick — and therefore no drain, no close, no terminal — can ever happen on
  that JVM. This is why the fixture blocks (`for (;;) Thread.sleep(MAX_VALUE)`)
  immediately after confirming admission and the durable PENDING write,
  rather than relying on timing.
- **Durable PENDING marker, verified before publication:** Phase A re-reads
  `ManagedVerseStateFileStore.minecraft(server).read()` (the real file) and
  requires an actual `DELETE`/`PENDING` operation intent for the target
  before writing the marker file; the marker itself carries this JVM's own
  `ProcessHandle.current().pid()` and a nonce.
- **PID-verified, non-broad kill:** the wrapper (`scripts/runProviderSafeDeleteCrashWithReceipt.sh`)
  captures `$!` from the exact process it spawned, polls for the marker,
  reads the marker's *own reported* PID back, and refuses to proceed unless
  it equals the PID it spawned — only then does it `kill -9` that exact PID.
  No name-based or broad kill, no sleep used as proof of anything (the sleep
  loop only polls for the marker's existence, bounded by a timeout that
  itself refuses on expiry), no graceful-shutdown substitution, no retained
  profile (the root must be absent before Phase A, refused otherwise).
- **Phase B waits for real exit and lock release:** the wrapper waits for
  the killed PID to actually disappear (`kill -0` polling, not `wait` on a
  process it didn't itself launch) before proceeding, and checks `lsof` on
  `world/session.lock` reports no holder before launching Phase B.
- **Expected crash exit recorded separately:** Phase A's exit status (after
  being forcibly killed) is written to its own `phase-a.forced-kill-exit`
  file and never conflated with Phase B's exit status, which the wrapper
  does require to be `0`.
- Phase B asserts via `runtime.listManaged()`/`beginAcquireReadyRead` that
  the target is `QUARANTINED` and unreadable, the unrelated verse is still
  `READY`, and the target's on-disk storage directory still exists (an
  interrupted delete must never have removed anything), then writes its own
  result receipt and requests a normal graceful stop (`server.halt(false)`)
  — Phase B is the recovery check, not another crash.

## 4. Corrected property wording (this document previously over-claimed)

The prior draft said failures "leave the world still loaded." That is true
only for `FAILED_SAVE`, confirmed by reading `QuiesceAndCloseOrchestrator`:
a save failure returns before any close/quarantine step runs, so the real
`ServerLevel` stays loaded and registered. A `FAILED_CLOSE` or cleanup
failure happens after save succeeds, inside the close/cleanup steps, which
**do** call `quarantine()` on failure — the source may then be absent from
`levels`, and `SafeDeleteOperation.finish()` always calls its own
`quarantine()` for any non-`SAVED_AND_CLOSED` receipt regardless. The
corrected property: **every failure must preserve data (nothing is deleted
or corrupted) and must leave the target durably quarantined; only
`FAILED_SAVE` additionally guarantees the exact same loaded instance is
still present and registered.** The planned fixture will assert the
outcome's truthful source presence per failure kind rather than one blanket
"still loaded" claim, and will compare deliberate sentinel content (not
whole-file byte identity, since an ordinary save legitimately rewrites
bytes) and the absence of removal/rename for protected targets.

## Still not resolved / not done

- The in-process success/occupied-refusal/busy-refusal/`FAILED_SAVE` fixture
  (property 1's other half) is not yet written.
- Nothing above has been executed. `bash -n` syntax-checked the wrapper
  script; `compileJava`/`compileTestJava` passed for the Java source; the
  two launch scripts generate successfully with the hook-bearing corrected
  provider explicitly selected. No marker, no kill, no Phase B run, no
  session.lock check has actually happened yet.
- This wrapper is narrower and less exhaustively hashed/guarded than the
  established `providerOrdinaryRestart` convention
  (`scripts/providerOrdinaryRestartReceiptLib.sh`) — deliberately, pending
  this review, rather than front-loading that level of hardening before any
  feedback on the approach itself.

## Explicitly not authorized by this document

No native run, no world creation, no filesystem mutation, no provider
invocation, no kill signal sent. Per the no-live-world-deletion gate already
in force on this ticket, return for review before any of that.
