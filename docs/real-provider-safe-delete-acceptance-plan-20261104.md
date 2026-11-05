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

## Round 3 fix (this update): a confirmed, pre-existing defect

Round 2's draft would have refused to even start. `confinedRoot` called
`server.getWorldPath(LevelResource.ROOT)` -- the WORLD save directory
(`gameDir/world`, since the wrapper sets `level-name=world`) -- and then
required *its own basename* to equal the registered *game*-directory name.
Those are never the same path, so the very first call would have thrown.
Separately, the wrapper wrote the nonce file at the game root but read/wrote
the marker and result at the world root, and the marker recorded the world
root under the key `root` while the wrapper compared it against the game
root. Found by review, not by running anything.

Fixed by modeling the two roots explicitly and testably:

- `ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(Path world)` is a
  new, server-free static method: given the world directory, it requires
  the world directory to be real/non-symlinked and named exactly `world`,
  derives the game root as its parent, requires the game root to be real/
  non-symlinked and named exactly the registered disposable directory, and
  requires every ancestor of the game root to be non-symlinked. `gameRoot`/
  `worldRoot` (both taking a `MinecraftServer`) call this.
- Nonce, marker, and result files all now live at the **game** root
  exclusively (the wrapper's `eula.txt`/`server.properties` root); dimension
  storage naturally stays under the **world** root
  (`world/dimensions/dynamicverse/<name>`). The marker's `root` key was
  renamed `gameRoot` to make the identity explicit, and `validateBeforeRuntime`/
  Phase A/Phase B were all updated to use the correct root consistently.
- `src/test/java/.../lifecycle/ProviderSafeDeleteCrashFixtureRootBindingTest.java`:
  8 non-native JUnit tests (no `MinecraftServer`, no native launch) against
  real temp directories -- the intended valid pair, a world directory not
  named `world`, a game root with the wrong name, a `world` directory that
  is not the expected game root's own child (same names, wrong relationship),
  a symlinked world directory, a symlinked game-root ancestor, and a
  nonexistent retained/foreign path. **Actually run**: 8/8 passed, 0
  failures, 0 errors.

## Round 3 input-receipt and process-ownership closures (this update)

The same review (Jira comment 51571) also found the round-2 input-receipt
and process-termination hardening incomplete. Closed here, wrapper-script-
only (no Java changes, so the prior 91/0/0/0 compile/test result still
applies):

- **Mod-folder directories weren't bound or hashed.** The generated launch
  script passes `-Dfml.modFolders=...` naming the main/test class and
  resource directories NeoForge actually loads from; `crash_require_generated`
  checked everything else about the generated script but not this literal.
  It now requires the exact expected four paths, and `verify_input_contract`
  content-hashes all four directories (via the existing `ordinary_tree_hash`/
  `ordinary_typed_entry_hash` helpers) immediately before each phase,
  requiring A's and B's hashes to match and recording them to
  `providerSafeDeleteCrash-receipts/mod-folder-hashes.txt`.
- **The legacy classpath was only hashed as a listing file, not by
  content.** `ordinary_hash "$legacy"` hashes the *text file naming* the
  legacy-classpath jars -- proof the two listings name the same paths, not
  that those jars have the same bytes. Switched to `ordinary_closure_hash`,
  which resolves and content-hashes every entry the listing names (the same
  helper already used for the normal classpath).
- **`processStartInstant` was written by the JVM but never read.** The
  wrapper now captures its own wall-clock timestamp immediately before
  spawning phase A, then after reading the marker, parses the JVM's
  self-reported `processStartInstant` and requires it to fall inside the
  window this wrapper could actually have spawned it in -- an identity
  signal from a different clock source than the `/proc` start-ticks check,
  both now checked before any kill.
- **A wrapper-side kill of phase B's `timeout` process could orphan the
  java process underneath it.** SIGKILL is never forwarded by the process
  that receives it, so the prior `cleanup()` trap -- which signaled only the
  PID bash reported for the backgrounded `timeout --kill-after=... ` job --
  could leave the actual server running (and its `session.lock` held) if
  the wrapper itself were interrupted or refused between launching phase B
  and `timeout`'s own bounded termination logic taking effect. Verified
  empirically (plain `ps`, not assumed from documentation) that `timeout`
  does not give its monitored child a separate process group of its own --
  the child inherits `timeout`'s group -- so wrapping the phase B launch in
  `setsid` (which, run without `-f`, execs in place rather than forking
  away, so the backgrounded PID bash reports IS the new group leader) lets
  `cleanup()` reach both via `kill -9 -- -$phase_b_pid`. Confirmed live: a
  `setsid timeout ... sleep` tree where the forced-kill path actually kills
  both members, versus killing only the `timeout` PID leaving its child
  running.

Re-verified after these changes: `bash -n` on the wrapper, and the
read-only preflight script re-run against freshly regenerated Phase A/B
launch scripts -- contract OK for both phases, provider hash matches
`4c4d70a90...`, normal closures match, legacy closure *contents* now match,
log4j configs match, all four mod-folder directory hashes computed, VM args
identical apart from the expected phase-only differences. Nothing has been
executed natively.

## Round 2 hardening

A second technical review of the first draft (97276b8) found the fixture/
runner "not yet safe or sufficient to run" and named five corrections,
addressed below, all still unexecuted:

- **Preflight moved before `ServerVerseRuntime.create`.** Added
  `ProviderSafeDeleteCrashFixture.validateBeforeRuntime(phase, server)`,
  wired into `DynamicVerse.serverStarting` exactly like
  `ProviderOrdinaryRestartFixture.validateBeforeRuntime`. Phase A's check
  refuses a retained prior marker/result. Phase B's check reads the marker,
  cross-checks its nonce/root against the wrapper-owned nonce file, and
  independently re-reads the real durable state file to confirm the exact
  recorded operation id/generation is still `PENDING` — all **before**
  production recovery runs, not after. `confinedRoot` also now requires the
  root's final path segment to equal the exact registered disposable
  directory name, not merely "any real directory."
- **Generated-launch/input contract enforced, not just file existence.**
  The wrapper now sources `scripts/providerOrdinaryRestartReceiptLib.sh`
  (its hash/closure helpers are generic, not ordinary-restart-specific) and
  adds a `crash_require_generated` check mirroring
  `ordinary_require_generated`: exact `cd '<root>'; exec` binding, exact
  classpath/VM-args/program-args file references, the exact
  `-Ddynamicverse.providerSafeDeleteCrashPhase=<phase>` property with no
  GameTest-namespace property present, and a `-classpath`-leading argfile.
  It then extracts the provider jar via `ordinary_provider_input` and
  requires its SHA-256 to equal the **hook-bearing** corrected provider's
  hash (`4c4d70a9...`, not the older SUPERFLAT-credited `5c7afbc7...` nor
  the stale repository default), for both phases, and requires both phases'
  full classpath closures (`ordinary_closure_hash`) to be identical. All of
  this was actually run (read-only, no process spawned) against the real
  generated files from this session's launch-script generation, and passed:
  `A: contract OK`, `B: contract OK`, both provider hashes matched exactly,
  both closures matched exactly.
- **Wrapper-owned, round-tripped nonce; atomic marker/result writes.** The
  wrapper now generates the one expected nonce itself and writes it to a
  nonce file *before* Phase A launches; Phase A echoes it into the marker,
  the wrapper verifies the echo, and Phase B's result additionally echoes it
  again, cross-checked by the wrapper after Phase B exits. Marker and result
  are now written via a `CREATE_NEW` staging sibling, `force(true)`, then
  `ATOMIC_MOVE` — no reader can ever observe a partially-written file.
  The wrapper installs an `EXIT` trap that kills the exact spawned Phase A
  PID if still alive on any refusal path, re-verifies PID ownership
  immediately before sending the kill signal (not only once at marker time),
  fails closed if `lsof` itself is unavailable instead of skipping the
  lock-release check, and now runs Phase B under `timeout 120s` with its own
  distinct refusal on timeout.
- **Phase B's assertions strengthened.** `targetRejectionCompleted` now
  requires the read-acquisition callback to have actually fired exactly
  once, with an explicit `VerseReservationPhase.FAILED` and an empty lease —
  a callback that simply never ran is no longer treated as "unreadable."
  Added `targetNeverReloaded` via the real `server.getLevel(TARGET_KEY) ==
  null` check. The unrelated verse's replay is now verified through a real
  acquired lease, `runtime.resolve(lease)` to an actual current `ServerLevel`,
  and that level's own sentinel block content — not listing state alone.
  Both phases now write a distinct sentinel block into the target and the
  unrelated verse (`REDSTONE_LAMP`/`GLOWSTONE`, the same technique
  `ProviderOrdinaryRestartFixture` already uses) and force a real save
  before Phase A's marker is published, so the target's confined on-disk
  storage is real content, not an empty directory. Phase A's marker now
  records the exact admitted operation's id and target generation, read
  back from the real durable file; Phase B's result echoes both, and the
  wrapper refuses a result whose operation id/generation doesn't match
  Phase A's.

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
  (property 1's other half) is **still not written**. This was explicitly
  asked for again in round 2 and was not delivered in this update — round 2
  prioritized the five corrections to the already-drafted crash fixture over
  starting a new one, since the existing draft was explicitly called "not
  yet safe... to run." Reusing the `ownsRuntime()`/dedicated-phase pattern
  already established for `seededRestartPhase` A/B (so this new fixture, not
  `DynamicVerse`'s own auto-created runtime, owns the one real
  `ServerVerseRuntime` instance for its run) is the intended approach for
  the next increment.
- Nothing above has been executed as a native server. `bash -n`
  syntax-checked the wrapper script; `compileJava`/`compileTestJava` passed
  for the Java source; the two launch scripts generate successfully with
  the hook-bearing corrected provider explicitly selected; the wrapper's
  full preflight contract/hash/closure logic was actually run (read-only,
  no process spawned) against those real generated files and passed. No
  marker, no kill, no Phase B run, no session.lock check has actually
  happened yet.
- Round 2 reused the established `providerOrdinaryRestart` convention's
  generic hash/closure helpers directly (by sourcing the same library) and
  added an analogous generated-launch contract check, so the gap with that
  convention is now mainly: no O1/O2-style pre-launch level.dat/state hash
  snapshotting across the exact same receipt tuple shape, and no retry-only
  profile variant. Those remain open if a future round wants that exact
  parity; not reproduced here given the different (crash/kill vs graceful)
  shape of this fixture.

## Explicitly not authorized by this document

No native run, no world creation, no filesystem mutation, no provider
invocation, no kill signal sent. Per the no-live-world-deletion gate already
in force on this ticket, return for review before any of that.
