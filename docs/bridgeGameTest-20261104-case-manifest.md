# bridgeGameTest 2026-11-04 case manifest

Reviewable listing of the 39 `@GameTest` cases executed by the `bridgeGameTest` run
reported in the `08457ba`-era comment (full log SHA-256
`e9086773603d80225ac59282e31c6f08bc2fb410af61d034864eb1ad4a2bc2d7`, exit `0 0 0`,
server log `All 39 required tests passed :)`). Enumerated directly from source
(`grep` over the `@GameTest` methods actually compiled into the run), not retyped
by hand; this file does not rerun anything — it is a manifest of what that
already-captured raw log covers.

Vanilla GameTest's own server log only names a failing test; a passing batch is
reported only as an aggregate count. "All 39 required tests passed" together
with this 32+7=39 manifest is the full claim: every one of the following passed,
none are unaccounted for.

## `io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderDuplicationBridgeGameTest` (32)

1. controlledBridgePropagatesSeedToBothReloads
2. controlledBridgeSeededCapabilityAndObservedSeedFailuresFailClosed
3. controlledBridgeDelayedLinkageErrorQuarantinesOnce
4. controlledBridgeCapabilityLinkageAndThrowingCurrentFailClosed
5. controlledBridgeSuccessRetainsGateUntilRestoration
6. controlledBridgeNullTerminalQuarantines
7. controlledBridgeFailedAndMismatchedTerminalsQuarantine
8. controlledBridgeCopyAndRestoreFailuresQuarantine
9. controlledBridgeReplayAndThrowingCompletionAreExactlyOnce
10. controlledBridgeDestinationRefusalsRetainOnlyRestoredSource
11. controlledBridgeAdmissionRefusalPreservesOriginalReadiness
12. controlledBridgeAdmissionRefusalAllowsSameDestinationRetry
13. controlledBridgeDeferredRegistrationRetainsGateAndRefusesWrongInstance
14. controlledRuntimeFacadeRetiresActualBridgeAdmission
15. controlledRuntimeCreateUsesSharedSeedAndQuarantinesUncertainResults
16. controlledRuntimeRejectsCreateWhenIntentCannotPersist
17. controlledRuntimeRecoversPendingIntentBeforeVerifiedSourceReplay
18. controlledRuntimeQuarantinesOnceWhenCreateTerminalPublicationFails
19. controlledRuntimeReplaysOnlyVerifiedRetainedReadyState
20. controlledRuntimeRejectsContradictorySeedBeforeStorageOrReload
21. controlledRuntimeCreatesExplicitSeedOnlyThroughPresentCapability
22. controlledRuntimeRefusesExplicitSeedWhenCapabilityIsUnavailable
23. controlledRuntimeQuarantinesExplicitSeedLinkageFailureOnce
24. controlledRuntimeReplaysRetainedSeedOnlyAfterLaterRegistration
25. controlledRuntimeQuarantinesRetainedSeedWithoutCapabilityBeforeStorage
26. controlledRuntimeQuarantinesSeededReloadLinkageFailureOnce
27. controlledRuntimeQuarantinesThrowingReplayLookupWithoutAbortingOtherTickWork
28. controlledRuntimeQuarantinesThrowingCreateLookupWithoutAbortingTick
29. controlledRuntimeRefusesSeededDuplicateBeforeAnyBridgeWork
30. controlledRuntimeDuplicatePersistsLoadedDestinationAndReplaysIt
31. controlledRuntimeQuarantinesOnceWhenDuplicateTerminalPublicationFails
32. controlledRuntimeRejectsStagedNondefaultDuplicateDestination

(#8, `controlledBridgeCopyAndRestoreFailuresQuarantine`, is the source of the
`WARNING: Closed dimension copier failed ...`/`WARNING: Provider duplicate
destination load failure`/`WARNING: Provider duplicate pipeline failure ...`
stack traces in the raw log — deliberate fault injection the test asserts
QUARANTINED on, not a failure.)

## `io.github.brooswitminecraft.dynamicverse.travel.NeoForgeVerseCommandsGameTest` (7)

33. lifecycleDuplicateAndListBoundary
34. runtimeRetiresPendingAccessWithoutProviderRun
35. runtimeEnrollmentLeaseAndReservationBoundary
36. runtimeRetiresOpenLeaseAndPendingAccess
37. runtimeDeleteAdmissionIsSafeBeforeProviderOrStorageMutation
38. **runtimeReleasesNoEffectDuplicateReservationButPreservesUncertainQuarantine**
    — the targeted regression for the PR2 duplicate-admission/durable-uncertain-intent fix.
39. registeredCommandBoundary
