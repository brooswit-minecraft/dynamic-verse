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

## Round 3, sixth incremental review (Jira comment 51607): a real reflection bug in the ledger reader, plus binding/wording corrections

Reviewed against `9be475d`. Confirmed the requested preservation checks
and root binding are present, and that three independent offline probes
behave correctly. One concrete blocker remained, closed here:

- **`readTerminalLedgerSnapshot` resolved the field from the wrong
  class.** `server.getClass().getDeclaredField(...)` searches only the
  EXACT class it's called on, never inherited fields -- the provider
  mixin injects `quiesceAndCloseTerminalResults` onto `MinecraftServer`
  itself, but the concrete runtime server under a GameTest is a subclass
  that doesn't declare it directly, so the very first snapshot would have
  thrown `NoSuchFieldException` before either FAILED_SAVE close. Fixed by
  resolving from `MinecraftServer.class` directly (the exact known
  declaring class) rather than `server.getClass()` -- a declared field's
  value can be read from any instance that is-a the declaring class,
  regardless of its concrete subtype. Verified the underlying Java
  behavior with a standalone throwaway probe (a parent/child class pair)
  before trusting either the bug report or the fix: `Child.class
  .getDeclaredField` on a parent-declared field throws exactly as
  predicted, and `Parent.class.getDeclaredField(...).get(childInstance)`
  correctly reads it.
- **Bound both observed terminal results to their intended identities.**
  `unrelatedResult.key()`/`targetResult.key()` are now required to equal
  the exact unrelated/target dimension keys, and each ledger map's own
  key is required to equal that result's own `generation()` -- on top of
  (not replacing) the existing unique-new-entry checks, the typed
  FAILED_SAVE/SAVE/READY/cause checks, and the once-per-JVM hook
  handling, all unchanged.
- **Two wording corrections** (flagged as non-blocking): the occupied/busy
  `ReadyVerse` comparisons are described as record/semantic equality
  across every field, not "byte-for-byte."

Re-verified without executing anything natively: a standalone Java
probe confirming the exact `getDeclaredField` inheritance behavior before
and after the fix; `compileJava`/`compileTestJava`/`test` 93/0/0/0
(unaffected); the in-process fixture's launch script regenerated and
inspected. No GameTest in this class has been run; no native run, kill,
or live-world action has occurred.

## Round 3, fifth incremental review (Jira comment 51604): typed FAILED_SAVE evidence, stronger preservation proofs, and binding the root claim to its own path

Reviewed against `056c523`. Credited the corrected names, one serialized
GameTest/runtime, bootstrap suppression, the terminal-cleanup listener,
armed-before-unrelated ordering, and the truthful loaded-source
expectation. Three more items, closed here:

- **Strong typed FAILED_SAVE evidence turned out to be available after
  all.** The pinned provider already retains its own typed
  `QuiesceAndCloseResult` before invoking any completion callback:
  `MinecraftServerMixin.quiesceAndCloseTerminalResults`, backed by
  `QuiesceAndCloseTerminals.results` -- confirmed directly against the
  patch and, since Mixin merges fields into the target class, against the
  real compiled mixin class via `javap` (field name and declared type
  exactly as expected, not assumed). No supported accessor exists, so
  `readTerminalLedgerSnapshot` is a narrowly-scoped, read-only reflective
  snapshot of that one field (and the private `results` map inside it),
  validating every value's exact declared type as it reads it and failing
  the fixture outright -- never silently falling back to the generic
  `QUARANTINED` outcome -- if the pinned shape doesn't match. The
  FAILED_SAVE phase now diffs the ledger's key set immediately before and
  after each of the unrelated and target deletes (never inspecting a
  generation number assigned by the provider ahead of time, and never
  touching the hook a second time or resetting its once-per-JVM
  consumption): the unrelated delete's new entry must be a clean
  `SAVED_AND_CLOSED` with no failure, and the target's new entry must be
  `FAILED_SAVE`/`SAVE`/`READY` with `sourceStillLoaded()` true and a cause
  whose class name ends in `.TestOnlySaveFailureHook$TestOnlySaveFailure`
  -- the exact typed signal, not a generic quarantine standing in for it.
- **Several preservation assertions were still missing, not merely weak.**
  Added: a real `AtomicInteger` completion counter for the success
  scenario (a `set`-only reference cannot prove "exactly once"); an
  exact-instance comparison of the FAILED_SAVE target's `ServerLevel`
  before and after the failure (`==`, not merely non-null); a check that
  the target's durable DELETE operation intent is retained as `PENDING`
  (confirmed by reading `SafeDeleteOperation` directly that nothing ever
  updates it on a quarantine -- this IS the exact recovery intent a future
  restart would need to find); full-record (`.equals()`, every field, not
  only `.generation()`) comparison of the occupied/busy targets' durable
  `ReadyVerse` before and after their refused attempts; confirmation that
  neither refusal ever journals a new durable operation intent; a real
  sentinel block written and saved into each of the occupied/busy targets
  before the refusal and verified unchanged after; and the success
  scenario's explicit unloaded-level assertion, retained alongside the
  disk/catalog removal checks.
- **The root claim wasn't actually bound to its own path.** The marker had
  no field naming its own containing directory, so
  `root_is_exclusively_claimed` only checked shape -- an offline probe
  confirmed a marker copied verbatim into a different directory was
  wrongly accepted. `claimSafeDeleteCrashExecutionRoot.sh` now resolves
  and records a `canonicalRoot=` field, validates every ancestor (not only
  the immediate parent) is not itself a symlink before claiming, and the
  check now requires that field to equal the directory's own resolved
  path, requires exactly one of each field (catching a duplicate or
  conflicting line a plain `grep -q` would miss), and still requires the
  nonce's own format. The main wrapper no longer generates an unrelated
  fresh nonce for execution -- it reads and reuses the SAME nonce the
  claim recorded, carrying that one identity through claim → generated
  cwd/`expectedGameRoot` → execution. Added negative tests for a
  copied/foreign marker, a marker naming the wrong root, and markers with
  a duplicate or malformed nonce field.

Re-verified without executing anything natively: `compileJava`/
`compileTestJava`/`test` 93/0/0/0 (unaffected -- only the fixture's
internals changed, not its scenario count); the in-process fixture's
launch script regenerated and inspected; the committed offline harness
extended to 23/23, including a full end-to-end dry run (claim a fresh
root, confirm `root_is_exclusively_claimed` accepts it and the claim's own
nonce is readable for execution) against the real wrapper functions, not
a replica. No GameTest in this class has been run; no native run, kill,
or live-world action has occurred.

## Round 3, fourth incremental review (Jira comment 51596): six corrections to the in-process fixture and the root-provenance gap

Reviewed against `0f0d198`. Credited that the fixture and harness are now
accessible, the busy case uses a real lease, and the occupied case reaches
the production player-list check. Found six more issues, each verified
against the actual source before fixing (not assumed):

- **A fixed scenario name was too long.** `rp_scenario_failed_save_unrelated`
  is 33 characters; `ServerVerseRuntime.ownedCreateId` permits at most 32
  (`name.matches("[a-z0-9_-]{1,32}")`, confirmed by reading it directly) --
  its own create would have been rejected before any native run. Renamed to
  `rp_scenario_fs_unrelated`/`rp_scenario_fs_target` (24/21 chars), and added
  `ProviderSafeDeleteScenariosNamingTest` (non-native JUnit) asserting every
  fixed name against the exact contract regex, run as part of the ordinary
  `test` task.
- **The FAILED_SAVE assertion was backwards.** Read
  `QuiesceAndCloseOrchestrator.execute` directly: on a save failure it
  returns `terminal(FAILED_SAVE, SAVE, saveFailure)` immediately, **before**
  ever reaching the close step -- the source level is never closed. Its own
  state-machine test (`QuiesceAndCloseBridgeTest`) literally expects
  `"FAILED_SAVE/SAVE/READY"` (status/failureStage/sourceReadiness) for this
  exact case. `ServerVerseRuntime`'s own quarantine is bookkeeping only (it
  revokes gate readiness) and does not invent an unload. The fixture
  previously asserted `server.getLevel(fsTargetKey) == null`; fixed to
  assert the target's real level remains loaded.
- **Four independent runtime instances, plus `DynamicVerse`'s own bootstrap
  fifth, all reconciling the same durable file.** Each of the four
  `@GameTest` methods constructed its own `ServerVerseRuntime.create`, and
  `providerSafeDeleteScenarios` was not yet in `ownsRuntime()`, so
  `DynamicVerse.serverStarting` also built its own. Consolidated into ONE
  `@GameTest` method sharing ONE runtime, scenarios run strictly
  sequentially; added `providerSafeDeleteScenarios` to `ownsRuntime()` so
  the bootstrap instance is suppressed (mirroring the existing
  `providerSeededDuplicateIntegration` precedent) -- fixture-local, not a
  new production framework.
- **The FAILED_SAVE proof was too weak.** Deleting the unrelated verse
  *before* arming the hook only proves ordinary success, not that a
  non-matching armed key leaves the hook unconsumed. The hook is now armed
  *before* either FAILED_SAVE-scenario verse is even created; the unrelated
  (non-matching) verse is deleted *while already armed* for the target's
  different key, then the target is deleted. The armed system property is
  now restored to its original value in the same unified cleanup every
  outcome runs through (below) -- it was previously never restored.
  Strengthening the evidence beyond the facade's generic `QUARANTINED`
  further hit a real constraint: the hook fires at most once per JVM, so a
  throwaway low-level probe to inspect the typed cause would consume the
  one real attempt the production-facade assertion needs, and the
  orchestrator's typed cause is never surfaced through
  `ServerVerseRuntime`/`SafeDeleteOperation` to begin with (confirmed by
  reading `SafeDeleteOperation.finish()`/`quarantine()` directly -- no
  stage-specific error is persisted anywhere observable). Getting a typed
  signal would need either consuming the hook twice (impossible) or
  extending production mapping code (explicitly out of scope); the
  evidence this fixture retains is deliberately bounded to what the real,
  single armed attempt actually proves through the production facade:
  `QUARANTINED` outcome, catalog state, retained durable/disk data, and the
  unrelated verse's normal success under the same armed property.
- **Cleanup only ran on the success path.** `.thenExecute(runtime::close)`
  is never reached if a step fails or the test times out. Replaced with a
  `GameTestListener` registered once via `helper.testInfo.addListener(...)`
  (a real, public extension point -- confirmed via `javap` against the
  actual compiled class, not assumed): its `testPassed`/`testFailed`
  callbacks fire for every terminal outcome, including a genuine framework
  timeout, and release the open lease, remove the fixture's `FakePlayer`,
  restore the armed property, and close the runtime exactly once
  (`compareAndSet`-guarded). Added the stronger durable/disk assertions
  requested: success now also verifies the target's on-disk storage is
  actually removed and its durable READY/operation entries are gone (not
  just catalog/unloaded state); occupied/busy refusals now verify the
  durable READY generation is byte-identical before and after the refused
  attempt; FAILED_SAVE now also verifies the durable READY entry is
  retained and that a fresh `beginAcquireReadyRead` against the quarantined
  target is denied.
- **The execution-root check accepted any empty directory, not specifically
  one this invocation owned.** An emptiness check cannot prove provenance:
  ModDev's own `prepare*Run` task leaves an untouched directory looking
  identically empty whether it was just claimed or is an unrelated retained
  root. Replaced with an explicit claim stage,
  `scripts/claimSafeDeleteCrashExecutionRoot.sh <path>`: it is the ONLY
  place that ever creates the root, refuses anything but true absence, and
  relies on `mkdir`'s own atomicity for the actual exclusive claim (no
  separate check-then-create race window) before writing one marker file
  recording the claim (host, pid, UTC timestamp, a nonce). The main wrapper
  no longer calls `mkdir` on the root at all; `root_is_exclusively_claimed`
  verifies that exact marker is present and well-formed, refusing a merely
  empty directory, real content, or a symlink exactly as before. Confirmed
  empirically (not assumed) that regenerating the launch scripts against an
  already-claimed root leaves a pre-existing file byte-for-byte untouched,
  so claiming first and regenerating second is safe.

Re-verified without executing anything natively: `compileJava`/
`compileTestJava`/`test` 93/0/0/0 (2 new naming-contract tests; everything
else unaffected); both launch scripts (`providerSafeDeleteScenariosGameTest`,
and the two-JVM crash fixture's, re-regenerated after an earlier probe had
pointed them at a scratch path) regenerated against the accepted provider
jar and inspected -- correct `cd` target, namespace/property, and provider
hash all present; the committed offline harness extended to 19/19,
including the claim script's own exclusivity (refuses to re-claim, refuses
to adopt a pre-existing empty directory) and the marker's survival through
a modeled `prepare*Run` side effect. No native run, kill, or live-world
action has occurred.

## Round 3, third incremental review (Jira comment 51591): the retained test harness, and the execution-root/preparation-root separation

Confirmed the 51588 fixes against `804d6d3`. Asked for two more concrete
items, both done here, plus the separately-authorized in-process fixture
below:

- **The 10-case sourced-wrapper test harness is now committed**, at
  `scripts/testProviderSafeDeleteCrashWrapperOffline.sh` (it previously
  only existed as a scratch file, so the 10/10 claim could not be
  independently inspected or reproduced from the tree). It gained 4 more
  cases for this round's new `root_is_pristine_or_absent` (below), for 14
  total. **Run: 14/14 passed.**
- **The execution-root/preparation-root separation.** Regenerating these
  launch scripts for review -- ordinary preparation work, done every
  round -- makes ModDev's own `prepare*Run` task create the configured
  `gameDirectory` as a side effect; confirmed empirically it creates
  *exactly* the bare, empty directory and nothing else. Since the wrapper
  previously hardcoded the SAME path ModDev's run registration names,
  every round of ordinary script regeneration would have left the
  wrapper's own "requires an absent root" guard permanently tripped before
  a real run could ever begin -- and renaming to a different fixed path
  doesn't help, since ModDev touches whatever path `gameDirectory` names,
  confirmed by regenerating against a second, differently-named path and
  observing the same empty directory appear. Fixed two ways together:
  - `build.gradle`'s `gameDirectory` (and the matching
    `providerSafeDeleteCrashExpectedGameRoot` system property, kept
    derived from the exact same value so they can never drift apart) is
    now overridable via `-PsafeDeleteCrashGameDirectory=<path>`, defaulting
    to the existing stable preparation path. The wrapper itself now
    requires `DYNAMICVERSE_SAFE_DELETE_CRASH_EXECUTION_ROOT` to be set
    explicitly (sourcing for tests is unaffected: the wrapper's own
    `root` defaults to empty under `set -u`, and only `main()` -- never
    invoked by sourcing -- requires it non-empty), so a real run is never
    silently defaulted onto the same path every preparation round uses.
  - The actual mechanical fix: `root_is_pristine_or_absent` relaxes the
    absent-root check to also accept a directory that is absent **or**
    verified to have zero entries -- the exact state ModDev's prepare
    task leaves and nothing more. A root with any real content (an
    `eula.txt`, a marker, anything) is still refused exactly as before;
    nothing is deleted, reused, or weakened. Validated entirely offline
    (no native run): all four cases -- absent, genuinely empty, real
    content, symlinked -- behave correctly against synthetic directories,
    and the two *real* empty directories left over from this review's own
    script regenerations (the stable default and a differently-named
    probe) are both now correctly accepted.

## Prepared (not executed): the separate in-process real-provider safe-delete fixture

Authorized for preparation across 51562/51571/51577/51588 and explicitly
no longer gated on further status exchange (51588: "It need not wait
behind additional status exchanges"). `ProviderSafeDeleteScenariosGameTest`
(`src/main/java/.../lifecycle/`, registered the same way
`ProviderExplicitSuperflatGameTest` already is, via `DynamicVerse`'s
existing dispatch switch and a new `dynamicverse.providerSafeDeleteScenarios`
system property/Gradle run `providerSafeDeleteScenariosGameTest`) covers,
each in its own `@GameTest` method and each calling
`ServerVerseRuntime.create(server)` for real production wiring -- the
real `ManagedVerseStateFileStore` and real `DeleteGateway`/provider
bridge, not the `InMemoryManagedVerseStateStore`/fake gateway the
existing `NeoForgeVerseCommandsGameTest` confirmed-delete test uses:

- **Success**: a confirmed delete against a real, READY, unobstructed
  verse completes as `DeleteOutcome.DELETED`.
- **Occupied-refusal**: a real NeoForge `FakePlayer` (a genuine
  `ServerPlayer`, not a test double for the delete path) added to the
  target's own loaded level via `ServerLevel.addNewPlayer` makes
  `DeleteGateway.minecraft()`'s real `hasPlayers` check true, and
  `beginConfirmedDelete` is refused `ERR_PLAYERS_PRESENT` -- confirmed by
  reading `ServerVerseRuntime.beginConfirmedDelete`'s exact source, not
  assumed.
- **Busy-refusal**: read `VerseOperationGate`'s actual source directly
  (not taken on assumption) to find the real, deterministic path to
  `DeleteAdmission.ERR_VERSE_BUSY`: `beginConfirmedDelete`'s own readiness
  check (`gate.readiness(key).state() != READY`) is satisfied by an
  outstanding read lease (readiness ignores the `reads` counter), but
  `gate.beginDelete(key)` itself refuses whenever `entry.reads != 0` --
  so an open `beginAcquireReadyRead` lease, never closed, is what actually
  triggers `ERR_VERSE_BUSY` through the gate's own `IllegalStateException`
  path. (An earlier draft assumed calling `beginConfirmedDelete` twice on
  the same key would do this; reading the source directly showed the first
  call already flips `entry.ready` to `false`, so a second call would
  instead hit `ERR_VERSE_NOT_READY` -- caught before writing any fixture
  code, not discovered by running one.)
- **Exact-target FAILED_SAVE**: arms the real `TestOnlySaveFailureHook`
  (`dynamicdimensions.testOnlyFailSaveForKey`, matched by the armed
  dimension's exact `ResourceLocation#toString()`, global single-fire per
  JVM -- confirmed by reading the hook's actual source in the patch, not
  assumed) for one target only, after first deleting a separate,
  never-armed, unrelated verse normally. The armed target's delete is
  admitted normally (the injected failure fires during the real forced
  save, not at admission) and surfaces as `DeleteOutcome.QUARANTINED`,
  with its catalog entry retained as `QUARANTINED` and its on-disk storage
  directory still present -- never a silently-successful `DELETED`, and
  the unrelated verse's own successful delete proves the failure is
  scoped to the exact armed key, not global breakage.

**Verified without executing anything natively:** `compileJava` BUILD
SUCCESSFUL (the new class and `DynamicVerse`/`build.gradle` wiring
compile cleanly); `compileTestJava`/`test` unaffected, 91/0/0/0; the new
Gradle run's launch script was generated (read-only) against the accepted
hook-bearing provider jar and inspected directly: its own disposable root,
`-Dneoforge.enabledGameTestNamespaces=dynamicverse`,
`-Ddynamicverse.providerSafeDeleteScenarios=true`, and the exact expected
provider jar (`4c4d70a90...`) are all present and correct. No GameTest in
this class has been run.

## Round 3, second incremental review (Jira comment 51588): two confirmed runner defects

Reviewed against addea9b. Credited the root-binding/negative test, frozen
manifests, shared cleanup identity check, and Phase B process-group
containment. Found two more defects, each reproduced live with a harmless,
non-native probe before fixing:

- **Both manifest captures were unchecked command substitutions.**
  `pre_phase_a_manifest=$(capture_manifest ...)` and the phase-B equivalent
  had no `|| refuse` guard. `capture_manifest` calls `refuse`/`exit`
  internally on a helper failure, but that `exit` only terminates the
  SUBSHELL the command substitution creates; under `set -uo pipefail`
  without `-e`, the parent script was never told the assignment's command
  had failed, and continued with whatever partial/empty stdout had been
  produced. Reproduced with a direct probe: a simulated helper failure
  inside such a subshell left the parent running with an empty captured
  variable. Worse, if the same failure hit both phase A's and phase B's
  capture identically, two empty manifests would compare equal via `diff`.
  Fixed by adding an explicit `|| refuse` to both call sites, plus a new
  `require_manifest_schema`, called on both captures, that rejects a
  missing, partial, or empty manifest by checking each retained field is
  present and looks like the 64-hex hash it's supposed to be -- defense in
  depth against a helper that returns 0 but still produces malformed
  output. `main()` now checks and rejects both before the disposable root
  is created (pre-phase-A) and before phase B is launched (pre-phase-B).
- **`Properties.store` escapes `:` in VALUES, not only in keys.** Confirmed
  empirically: writing `processStartInstant=2026-11-05T01:12:10.123Z` via
  a real `Properties.store` produces the literal line
  `processStartInstant=2026-11-05T01\:12\:10.123Z` on disk. The wrapper
  read this field with the same plain `sed` extraction used for every
  other (colon-free) marker field, leaving the backslashes in place and
  breaking `date -u -d ...` parsing -- reproduced directly. Fixed by
  reusing the existing proper Java-properties reader
  (`ProviderSeededRestartProperties.java`, already used by the
  `providerOrdinaryRestart` wrapper for exactly this reason) via a new
  `read_process_start_instant` helper, rather than hand-rolling Java
  properties escaping rules in bash.

Also restructured for testability, per the same review's request for "a
source-level/mock failure-path test that actually exercises the wrapper
functions, not merely a separately retyped happy-path replica": everything
previously executed as bare top-level statements is now inside `main()`,
called only when the file is EXECUTED directly
(`[[ "${BASH_SOURCE[0]}" == "${0}" ]]`), not when it is sourced -- so a
test can `source` this exact file to get its real functions with zero
side effects. A new test sources the real wrapper and: reproduces the
original unguarded-capture bug and proves the `|| refuse` fix stops it;
injects a mocked helper failure into `capture_manifest` and confirms it's
caught; feeds `require_manifest_schema` a partial and an empty manifest
and confirms both are refused; diffs a real manifest against a
deliberately drifted copy via `require_manifests_match` and confirms
refusal, and against itself and confirms acceptance; and writes a real
`Properties.store`d marker with a colon-bearing `processStartInstant`,
proving the old `sed` read fails to parse while the new
`read_process_start_instant` round-trips correctly. Run: 10/10 passed.

Re-verified without executing anything natively: `compileJava`/
`compileTestJava`/`test` unaffected (91/0/0/0 -- this round touched only
the wrapper script); `bash -n`; sourcing the restructured wrapper produces
its real functions with no side effects (confirmed: `main` is a defined
function, never invoked, and the disposable root is not created); the
existing read-only contract/manifest preflight re-run against the real
generated files, unchanged results. Nothing has been executed natively.

Operational note (not a defect, nothing to fix): the disposable game root
currently exists on disk, empty, as a side effect of Gradle's
`prepareProviderSafeDeleteCrashPhase*Run` tasks (run while regenerating
the launch scripts during this review). The wrapper's own
`[[ ! -e "$root" ]] || refuse ...` check will correctly refuse to proceed
while it exists -- expected, fail-closed behavior, not something this
non-native work should remove.

## Round 3, incremental review (Jira comment 51577): four more closures

Reviewed against b8c706f (the game-root/world-root fix, not yet including
the input-receipt/process-ownership closures above). Confirmed useful but
found four more source-backed gaps; closed here (`build.gradle`, the
wrapper script, the Java fixture, and its tests -- no native execution):

- **`cleanup()` didn't check process-start identity, only `kill -0`.** A
  refusal issued because the primary path's identity check failed would
  still run the EXIT trap, which -- checking only that the PID exists --
  could kill whatever unrelated process the kernel had since reused that
  PID for, defeating the refusal it was supposed to honor. Introduced
  `kill_if_still_owned`, which re-checks `/proc` start ticks before
  signaling, and routed BOTH the primary kill path and `cleanup()` through
  it; `phase_b_start` is now captured and checked the same way phase A's
  already was.
- **A trapped INT/TERM handler that returns lets the script fall through
  into the next statement**, rather than actually stopping -- an
  interruption during phase A's wait loop could otherwise continue on into
  launching phase B. Split the EXIT trap (`cleanup`) from a new
  `on_terminating_signal`, which cleans up, clears all traps, and
  re-delivers the same signal to the wrapper's own PID so it actually
  terminates.
- **No immutable pre-phase-A manifest.** `verify_input_contract` recomputed
  both phases' hashes at each call and compared them to each other; if a
  build changed shared inputs between calls, both recomputations could
  still agree while silently differing from what phase A actually ran.
  Replaced with `capture_manifest`, called once before phase A ever
  launches and stored (`pre_phase_a_manifest`); phase B's freshly captured
  manifest is compared against that frozen snapshot via
  `require_manifests_match`, not against a re-derived phase A snapshot.
  The retained manifest also newly binds the legacy classpath by content
  (not just the listing file), all four `-Dfml.modFolders=` directories
  (now also bound into the generated-script contract check itself), the
  exact java executable's own binary, and the program arguments -- not
  only the normal classpath and VM args as before.
- **`diff ... || true` could treat a diff ERROR as an empty-output
  success.** `require_manifests_match` now captures diff's exit status
  explicitly and fails closed on anything other than 0 (identical),
  distinguishing "differences found" (1, refused with the diff shown) from
  "diff itself could not run" (2+, also refused). Verified with a
  deliberately corrupted manifest that the check actually refuses rather
  than silently passing.
- **`gameRootFromWorldRoot` accepted any correctly-named/shaped pair, not
  only the one true root.** The round-3 test
  `aForeignWorldDirectoryElsewhereWithTheRightNamesIsStillAcceptedOnlyIfCanonicallyRelated`
  explicitly asserted this as acceptable, contradicting the confinement
  requirement. `build.gradle` now supplies the exact registered absolute
  game-root path via a new system property
  (`dynamicverse.providerSafeDeleteCrashExpectedGameRoot`) on both run
  configs; `gameRootFromWorldRoot` takes it as an explicit parameter and
  requires an exact match, not merely a basename/shape match. The
  contradicting test was replaced with
  `aForeignPairElsewhereWithTheRightNamesAndShapeIsStillRefusedUnlessItIsTheExactExpectedRoot`,
  which asserts the opposite (refusal) for the same kind of foreign pair.
  The two symlink-dependent tests that silently returned (counting as a
  pass) when this environment cannot create symlinks now call
  `Assumptions.abort(...)` instead, so that environment limitation shows
  up as a reported skip, never a silent pass.

Re-verified without executing anything natively: `compileJava`/
`compileTestJava`/`test` 91/0/0/0 (one test renamed, net count unchanged);
`bash -n` on the wrapper; both launch scripts regenerated against the
corrected `build.gradle` (confirmed the new system property reaches the
generated VM args, identically for A and B); a read-only replica of
`crash_require_generated`/`verify_phase_sanity`/`capture_manifest`/
`require_manifests_match` run against those real regenerated files --
phase A and B sanity both OK, a 29-line manifest captured for each, the
two manifests match, and a deliberately corrupted copy is correctly
refused as "inputs changed" rather than silently accepted. The
`setsid`+group-kill mechanism from the prior closure was also re-verified
empirically (a throwaway `setsid timeout ... sleep` tree, confirming the
forced-kill path kills both members, not only `timeout`). Nothing has been
executed natively.

## Round 3 fix: a confirmed, pre-existing defect

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
