# Safe-delete / explicit-seed SUPERFLAT reconciliation (2026-11-04)

Per MINECRAFT-470 comment 51424: reconciles the local-only, uncommitted
`minecraft-470-safe-delete-continuation` checkout (base commit `b5bf6b5`, at
`/tmp/m470-codey-command-continuation-20261102`, preserved untouched) onto the
current, already-reviewed `minecraft-470-retained-continuation` head, as its
own separate branch — not merged into, and not rewriting, PR #2.

## Base / head

- Base: `minecraft-470-retained-continuation` @ `0d254468e3757e63a6dca14a5ea6358777e91cf9`
  (includes the reviewed duplicate-admission REJECTED_NO_EFFECT/REJECTED_UNCERTAIN
  fix, the durable-uncertain-intent restart fix, the recovered provider patch,
  and its admission-order correction).
- This branch: `minecraft-470-safe-delete-reconciled` — see `git log` for the exact
  head once pushed; one commit on top of the base above.

## What was NOT ported (superseded, deliberately dropped)

The source checkout independently reimplemented a duplicate-admission rollback
distinction conceptually equivalent to, but older and less precise than, the
one already reviewed and merged on this branch's base
(`ProviderDuplicationBridge.java` and the `beginDuplicate` hunk of
`ServerVerseRuntime.java`). Those two hunks were **not** ported — this branch
keeps the current, already-reviewed `REJECTED_NO_EFFECT`/`REJECTED_UNCERTAIN`
logic exactly as merged, unchanged.

## Changed-file manifest (this commit only)

Modified (surgically merged — delete/SUPERFLAT hunks only, duplicate-admission
hunks excluded per above):
- `build.gradle` — `reviewGameTest`/`providerExplicitSuperflatGameTest` run
  registrations, `reviewGameTestAdditionalRuntimeClasspath` dependency.
- `src/main/java/.../DynamicVerse.java` — `ProviderExplicitSuperflatGameTest`
  selector, `beginConfirmedDelete` facade delegation.
- `src/main/java/.../lifecycle/ManagedVerseStateStore.java` — `State.withoutReady`.
- `src/main/java/.../lifecycle/ProviderCreateIntegrationGameTest.java` —
  `sameDefaultSuperflat` visibility widened (package-private) for reuse by the
  new SUPERFLAT fixture.
- `src/main/java/.../lifecycle/ServerVerseRuntime.java` — `DeleteGateway`
  field/factory/interface, `beginConfirmedDelete`, `beginDelete` now returns
  `ERR_CONFIRMATION_REQUIRED` when a delete gateway exists, `tick()`/`close()`
  drive `pendingDeletes`, `replayReady` skips a QUARANTINED (interrupted
  delete) key. The `beginDuplicate` admission logic is untouched from base.
- `src/main/java/.../lifecycle/VerseLifecycleCommandService.java` —
  `beginConfirmedDelete` default method, expanded `DeleteAdmission`
  (`ADMITTED`, `ERR_CONFIRMATION_REQUIRED`, `ERR_PLAYERS_PRESENT`,
  `ERR_VERSE_BUSY`, `ERR_PROVIDER_UNAVAILABLE`), new `DeleteOutcome` enum.
- `src/main/java/.../lifecycle/VerseOperationGate.java` — `beginDelete`/
  `DeleteReservation`.
- `src/main/java/.../lifecycle/copy/OwnedDimensionCatalog.java` —
  `unregisterIfSame` (used by the delete journal; the duplicate-admission
  rollback path on this branch's base already uses plain `unregister`,
  unchanged).

Added (verbatim from the source checkout, no changes needed):
- `src/main/java/.../lifecycle/delete/{SafeDeleteOperation,ConfinedDimensionDeleter}.java`
- `src/test/java/.../lifecycle/delete/{SafeDeleteOperationTest,ConfinedDimensionDeleterTest}.java`
- `src/main/java/.../lifecycle/ProviderExplicitSuperflatGameTest.java`
- `src/main/resources/data/dynamicverse_review/structure/empty.nbt`

Not ported (already present on this branch's base, unrelated to this
reconciliation): `ClosedDimensionReceipt.java`/`ClosedDimensionStatus.java`
were already committed upstream of `b5bf6b5` (commit `90b5d95`); the source
checkout's copies are identical and required no action.

## Reproducible provider build inputs

Unchanged from the base branch (`docs/provider-source-reproducibility.md`):
`JAVA_HOME=/tmp/temurin21`, `-PreviewedDynamicDimensionsClasses=...` pointing at
a build of `provider-patches/dynamicdimensions-reviewed-seed-close.patch`
applied to `TeamGalacticraft/DynamicDimensions` @ `ecfbdf694d4f4c103404a77858d00c54a91b8020`,
built with `-PmojarnSource=...` against a `TeamGalacticraft/mojarn` checkout
pinned to `beb2addf7ec99a398b6a243cdcec73f4f33f6b01`. This reconciliation does
not change any of that; it only adds dynamic-verse-side delete/SUPERFLAT code
that compiles against the same `reviewedDynamicDimensionsClasses`/
`acceptedDynamicDimensionsJar` as before.

## Validation performed

- `JAVA_HOME=/tmp/temurin21 ./gradlew compileJava --no-daemon`: BUILD SUCCESSFUL
  (first attempt, no iteration needed after the surgical merge).
- `JAVA_HOME=/tmp/temurin21 ./gradlew compileTestJava --no-daemon`: BUILD SUCCESSFUL.
- `JAVA_HOME=/tmp/temurin21 ./gradlew test --no-daemon`: 83 tests / 0 failures /
  0 errors / 0 skipped — the 64-test base plus `SafeDeleteOperationTest` (13),
  `ConfinedDimensionDeleterTest` (6), and the pre-existing
  `FailClosedDeleteAdmissionTest` (1, now exercised alongside the expanded
  `DeleteAdmission` enum).

**No native GameTest run was performed on this candidate** — not
`providerExplicitSuperflatGameTest`, not any delete-path native run, not even
`bridgeGameTest` (which this candidate doesn't functionally change). Per
instruction, that disposable runtime validation waits for independent review
of this reconciled diff first.

## Preserved vs. changed acceptance evidence

- **Unchanged, still applicable:** every duplicate-admission/restart/provider
  finding already reviewed on this branch's base (10613d9/ce6852b/08457ba/
  0d25446) — this commit does not touch that code path.
- **O1/O2 ordinary-restart receipts, prior duplicate-retry receipts (native
  GameTest runs recorded earlier in ticket history):** source identities for
  the files those receipts covered (`ServerVerseRuntime`'s create/duplicate
  paths, `ProviderOrdinaryRestartFixture`, etc.) are unchanged by this
  commit — those receipts remain applicable as evidence for those specific
  slices. They are **not** evidence for safe-delete or SUPERFLAT, which they
  never exercised.
- **Safe-delete (`SafeDeleteOperation`/`ConfinedDimensionDeleter`/
  `beginConfirmedDelete`):** new in this commit. Only unit-level evidence
  above; no native runtime/GameTest acceptance yet. Not safe-delete *runtime*
  acceptance — preparation evidence only, same as review labeled it.
- **Explicit-seed SUPERFLAT (`ProviderExplicitSuperflatGameTest`):** new in
  this commit, ported verbatim, never run natively here. Remains open
  M470 acceptance, unchanged by this reconciliation.

## Not authorized by this commit

No merge, no live/user-world action, no formal approval, no native runtime
validation beyond what's listed above.
