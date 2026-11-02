package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ServerVerseRuntime;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.InMemoryManagedVerseStateStore;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseStateStore;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.OptionalLong;

/**
 * Source-only ModDev GameTest harness. It composes the actual bridge, gate, and
 * coordinator with controlled ports; do not execute it without serialized runtime approval.
 */
public final class ProviderDuplicationBridgeGameTest {
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgePropagatesSeedToBothReloads(GameTestHelper helper) throws Exception {
        Scenario scenario = scenario(helper.getLevel());
        scenario.bridge.registerReady(scenario.source.id(), "old", 1);
        require(scenario.bridge.beginDuplicate(scenario.source.id(), scenario.destination.id(), OptionalLong.of(9123417733L), scenario.completion())
                == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "seeded admission");
        scenario.provider.deliver(success(scenario.source.id(), 7));
        require(scenario.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.LOADED
                && scenario.provider.sourceSeed.equals(OptionalLong.of(9123417733L))
                && scenario.provider.destinationSeed.equals(OptionalLong.of(9123417733L)), "same seed reaches source and destination reload");
        helper.succeed();
    }
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void controlledBridgeSeededCapabilityAndObservedSeedFailuresFailClosed(GameTestHelper helper) throws Exception {
        Scenario unsupported = scenario(helper.getLevel()); unsupported.provider.seededCapability = false;
        require(unsupported.bridge.beginDuplicate(unsupported.source.id(), unsupported.destination.id(), OptionalLong.of(9), unsupported.completion()) == ProviderDuplicationBridge.BridgeAdmission.REJECTED && unsupported.provider.admissions.get() == 0, "seed capability rejects before close");
        Scenario sourceWrong = scenario(helper.getLevel()); sourceWrong.provider.sourceObservedSeed = 8; sourceWrong.bridge.registerReady(sourceWrong.source.id(), "old", 1);
        require(sourceWrong.bridge.beginDuplicate(sourceWrong.source.id(), sourceWrong.destination.id(), OptionalLong.of(9), sourceWrong.completion()) == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "source admitted"); sourceWrong.provider.deliver(success(sourceWrong.source.id(), 7)); require(sourceWrong.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.QUARANTINED, "wrong source seed quarantines");
        Scenario destinationWrong = scenario(helper.getLevel()); destinationWrong.provider.sourceObservedSeed = 9; destinationWrong.provider.destinationObservedSeed = 8; destinationWrong.bridge.registerReady(destinationWrong.source.id(), "old", 1);
        require(destinationWrong.bridge.beginDuplicate(destinationWrong.source.id(), destinationWrong.destination.id(), OptionalLong.of(9), destinationWrong.completion()) == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "destination admitted"); destinationWrong.provider.deliver(success(destinationWrong.source.id(), 7)); require(destinationWrong.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.RESTORED, "wrong destination seed retains restored source"); helper.succeed();
    }
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void controlledBridgeDelayedLinkageErrorQuarantinesOnce(GameTestHelper helper) throws Exception {
        Scenario scenario = admittedScenario(helper.getLevel()); scenario.provider.throwDestinationLinkage = true;
        scenario.provider.deliver(success(scenario.source.id(), 7));
        require(scenario.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.RESTORED && scenario.completions.get() == 1,
                "destination linkage retains verified restored source once");
        assertReady(scenario.gate, scenario.source.id(), "restored", 7);
        require(scenario.gate.tryAcquireReadyRead(scenario.destination.id()) == null, "destination stays unavailable"); helper.succeed();
    }
    @GameTest(templateNamespace = "dynamicverse", template = "empty") @PrefixGameTestTemplate(false)
    public void controlledBridgeCapabilityLinkageAndThrowingCurrentFailClosed(GameTestHelper helper) throws Exception {
        Scenario capability = scenario(helper.getLevel()); capability.provider.throwCapabilityLinkage = true;
        require(!capability.bridge.supportsSeededReload(), "public runtime-facing capability probe fails closed");
        require(capability.bridge.beginDuplicate(capability.source.id(), capability.destination.id(), OptionalLong.of(9), capability.completion()) == ProviderDuplicationBridge.BridgeAdmission.REJECTED && capability.provider.admissions.get() == 0, "capability linkage before close");
        Scenario current = admittedScenario(helper.getLevel()); current.provider.delayDestinationObservation = true; current.provider.deliver(success(current.source.id(), 7));
        require(current.provider.pendingDestinationObservation != null && current.outcome.get() == null, "destination observation is delayed");
        current.provider.throwCurrent = true; current.provider.completeDestinationObservation(true);
        require(current.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.QUARANTINED && current.completions.get() == 1, "throwing current completes once"); helper.succeed();
    }
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeSuccessRetainsGateUntilRestoration(GameTestHelper helper) throws Exception {
        Scenario scenario = scenario(helper.getLevel());
        scenario.bridge.registerReady(scenario.source.id(), "old", 1);

        require(scenario.bridge.beginDuplicate(scenario.source.id(), scenario.destination.id(), scenario.completion())
                == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "admission");
        scenario.provider.deliver(success(scenario.source.id(), 7));

        require(scenario.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.LOADED, "loaded outcome");
        require(scenario.copies.get() == 1 && scenario.provider.loads.get() == 1 && scenario.provider.destinationLoads.get() == 1 && scenario.completions.get() == 1,
                "exact success counts");
        assertReady(scenario.gate, scenario.source.id(), "restored", 7);
        assertReady(scenario.gate, scenario.destination.id(), "destination", 7);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeNullTerminalQuarantines(GameTestHelper helper) throws Exception {
        Scenario scenario = admittedScenario(helper.getLevel());
        scenario.provider.deliver(null);
        assertQuarantinedOnce(scenario, "null terminal", 0, 0);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeFailedAndMismatchedTerminalsQuarantine(GameTestHelper helper) throws Exception {
        Scenario failed = admittedScenario(helper.getLevel());
        failed.provider.deliver(new ProviderQuiesceCloseTerminal(failed.source.id(), 7, true, ClosedDimensionStatus.SAVED_AND_CLOSED, true));
        assertQuarantinedOnce(failed, "failed terminal", 0, 0);

        Scenario mismatched = admittedScenario(helper.getLevel());
        mismatched.provider.deliver(success("dynamicverse:other", 7));
        assertQuarantinedOnce(mismatched, "mismatched terminal", 0, 0);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeCopyAndRestoreFailuresQuarantine(GameTestHelper helper) throws Exception {
        Scenario copyFailure = admittedScenario(helper.getLevel());
        copyFailure.throwCopy = true;
        copyFailure.provider.deliver(success(copyFailure.source.id(), 7));
        assertQuarantinedOnce(copyFailure, "copy failure", 1, 0);

        Scenario throwingRestore = admittedScenario(helper.getLevel());
        throwingRestore.provider.throwRestore = true;
        throwingRestore.provider.deliver(success(throwingRestore.source.id(), 7));
        assertQuarantinedOnce(throwingRestore, "throwing restore", 1, 1);

        Scenario nullRestore = admittedScenario(helper.getLevel());
        nullRestore.provider.nullRestore = true;
        nullRestore.provider.deliver(success(nullRestore.source.id(), 7));
        assertQuarantinedOnce(nullRestore, "null restore", 1, 1);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeReplayAndThrowingCompletionAreExactlyOnce(GameTestHelper helper) throws Exception {
        Scenario scenario = admittedScenario(helper.getLevel());
        scenario.throwCompletion = true;
        ProviderQuiesceCloseTerminal terminal = success(scenario.source.id(), 7);
        scenario.provider.deliver(terminal);
        scenario.provider.deliver(terminal);

        require(scenario.copies.get() == 1 && scenario.provider.loads.get() == 1 && scenario.provider.destinationLoads.get() == 1 && scenario.completions.get() == 1,
                "replay or callback replay");
        assertReady(scenario.gate, scenario.source.id(), "restored", 7);
        assertReady(scenario.gate, scenario.destination.id(), "destination", 7);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeDestinationRefusalsRetainOnlyRestoredSource(GameTestHelper helper) throws Exception {
        Scenario absent = admittedScenario(helper.getLevel());
        absent.provider.copiedDirectory = false;
        absent.provider.deliver(success(absent.source.id(), 7));
        assertSourceOnlyRestored(absent, "missing copied directory", 0);

        Scenario foreign = admittedScenario(helper.getLevel());
        foreign.provider.foreignDestination = true;
        foreign.provider.deliver(success(foreign.source.id(), 7));
        assertSourceOnlyRestored(foreign, "foreign destination", 1);

        Scenario wrongGeneration = admittedScenario(helper.getLevel());
        wrongGeneration.provider.wrongDestinationGeneration = true;
        wrongGeneration.provider.deliver(success(wrongGeneration.source.id(), 7));
        assertSourceOnlyRestored(wrongGeneration, "wrong destination generation", 1);

        Scenario replacedSource = admittedScenario(helper.getLevel());
        replacedSource.provider.replaceSourceDuringDestinationObservation = true;
        replacedSource.provider.deliver(success(replacedSource.source.id(), 7));
        assertQuarantinedOnce(replacedSource, "source replaced during destination observation", 1, 1);

        Scenario throwingReplacedSource = admittedScenario(helper.getLevel());
        throwingReplacedSource.provider.replaceSourceDuringDestinationLoad = true;
        throwingReplacedSource.provider.throwDestinationLoad = true;
        throwingReplacedSource.provider.deliver(success(throwingReplacedSource.source.id(), 7));
        assertQuarantinedOnce(throwingReplacedSource, "source replaced before throwing destination load", 1, 1);

        Scenario nullReplacedSource = admittedScenario(helper.getLevel());
        nullReplacedSource.provider.replaceSourceDuringDestinationLoad = true;
        nullReplacedSource.provider.nullDestinationLoad = true;
        nullReplacedSource.provider.deliver(success(nullReplacedSource.source.id(), 7));
        assertQuarantinedOnce(nullReplacedSource, "source replaced before null destination load", 1, 1);

        Scenario staleRestore = admittedScenario(helper.getLevel());
        staleRestore.provider.wrongRestoreGeneration = true;
        staleRestore.provider.deliver(success(staleRestore.source.id(), 7));
        assertQuarantinedOnce(staleRestore, "wrong restored source generation", 1, 1);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeAdmissionRefusalPreservesOriginalReadiness(GameTestHelper helper) throws Exception {
        Scenario scenario = scenario(helper.getLevel());
        scenario.provider.admit = false;
        scenario.bridge.registerReady(scenario.source.id(), "old", 1);

        require(scenario.bridge.beginDuplicate(scenario.source.id(), scenario.destination.id(), scenario.completion())
                == ProviderDuplicationBridge.BridgeAdmission.REJECTED, "admission must refuse");
        require(scenario.provider.terminal == null && scenario.copies.get() == 0 && scenario.provider.loads.get() == 0
                && scenario.completions.get() == 0, "refusal must have no terminal work");
        assertReady(scenario.gate, scenario.source.id(), "old", 1);
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeAdmissionRefusalAllowsSameDestinationRetry(GameTestHelper helper) throws Exception {
        Scenario scenario = scenario(helper.getLevel());
        scenario.bridge.registerReady(scenario.source.id(), "old", 1);
        scenario.provider.admit = false;
        require(scenario.bridge.beginDuplicate(scenario.source.id(), scenario.destination.id(), scenario.completion())
                == ProviderDuplicationBridge.BridgeAdmission.REJECTED, "first admission refused");
        scenario.provider.admit = true;
        require(scenario.bridge.beginDuplicate(scenario.source.id(), scenario.destination.id(), scenario.completion())
                == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "same destination retry admitted");
        scenario.provider.deliver(success(scenario.source.id(), 7));
        require(scenario.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.LOADED && scenario.copies.get() == 1
                && scenario.provider.loads.get() == 1 && scenario.provider.destinationLoads.get() == 1, "retry performed only successful work");
        helper.succeed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeDeferredRegistrationRetainsGateAndRefusesWrongInstance(GameTestHelper helper) throws Exception {
        Scenario eventual = admittedScenario(helper.getLevel());
        eventual.provider.delaySourceObservation = true;
        eventual.provider.delayDestinationObservation = true;
        eventual.provider.deliver(success(eventual.source.id(), 7));
        require(eventual.completions.get() == 0 && eventual.gate.tryAcquireReadyRead(eventual.source.id()) == null,
                "source remains unavailable during deferred registration");
        eventual.provider.completeSourceObservation(true);
        require(eventual.completions.get() == 0 && eventual.gate.tryAcquireReadyRead(eventual.destination.id()) == null,
                "destination remains unavailable during deferred registration");
        eventual.provider.completeDestinationObservation(true);
        require(eventual.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.LOADED && eventual.completions.get() == 1,
                "eventual exact registration publishes once");

        Scenario wrong = admittedScenario(helper.getLevel());
        wrong.provider.delayDestinationObservation = true;
        wrong.provider.deliver(success(wrong.source.id(), 7));
        wrong.provider.completeDestinationObservation(false);
        assertSourceOnlyRestored(wrong, "wrong/timeout destination registration", 1);
        helper.succeed();
    }

    /** Compiled-only facade-to-real-bridge composition: controlled ports, no native provider or world lifecycle. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeFacadeRetiresActualBridgeAdmission(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        ControlledCreate create = new ControlledCreate(level);
        AtomicReference<ControlledProvider> controlledProvider = new AtomicReference<>();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor -> descriptor == create.created
                        ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(),
                        descriptor.id() + "@" + Integer.toHexString(System.identityHashCode(level)), level.getSeed()) : null,
                (catalog, gate) -> {
                    ControlledProvider provider = new ControlledProvider(gate, "dynamicverse:runtime-source", "dynamicverse:runtime-copy");
                    controlledProvider.set(provider);
                    ProviderDuplicationBridge bridge = new ProviderDuplicationBridge(catalog, gate, provider,
                            intent -> new ClosedDimensionCopyRequest(Path.of("/tmp"), Path.of("runtime-source"), Path.of("runtime-copy"), intent.sourceKey(), intent.generation()),
                            (request, receipt, fence) -> { });
                    return new ServerVerseRuntime.BridgeGateway() {
                        @Override public void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException { bridge.registerReady(key, identity, generation); }
                        @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String sourceKey, String destinationKey,
                                                                                                   java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException {
                            return bridge.beginDuplicate(sourceKey, destinationKey, completion);
                        }
                    };
                }, create, new InMemoryManagedVerseStateStore());
        require(runtime.beginCreate("runtime-source", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "controlled producer create admitted");
        runtime.tick();
        OwnedDimensionDescriptor source = create.created;
        require(source != null && runtime.listManaged().stream().anyMatch(item -> item.name().equals(source.id())
                && item.state() == ManagedVerseListing.State.READY), "controlled producer publishes runtime source READY");
        AtomicReference<VerseLifecycleCommandService.DuplicateOutcome> terminal = new AtomicReference<>();
        AtomicInteger terminalCount = new AtomicInteger();
        AtomicInteger refusedCallbacks = new AtomicInteger();
        require(runtime.beginDuplicate(source.id(), "dynamicverse:runtime-copy", outcome -> { terminal.set(outcome); terminalCount.incrementAndGet(); })
                        == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED, "facade admits through actual bridge");
        VerseLifecycleCommandService.DuplicateAdmission sameDestination = runtime.beginDuplicate(source.id(), "dynamicverse:runtime-copy",
                ignored -> refusedCallbacks.incrementAndGet());
        require(sameDestination == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_ALREADY_EXISTS,
                "same destination guard expected ERR_VERSE_ALREADY_EXISTS actual=" + sameDestination);
        VerseLifecycleCommandService.DuplicateAdmission busySource = runtime.beginDuplicate(source.id(), "dynamicverse:runtime-busy-copy",
                ignored -> refusedCallbacks.incrementAndGet());
        require(busySource == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_BUSY,
                "fresh destination reaches bridge busy gate expected ERR_VERSE_BUSY actual=" + busySource);
        require(controlledProvider.get().admissions.get() == 1 && refusedCallbacks.get() == 0,
                "refused duplicate requests do not enter provider work or invoke completion callbacks");
        runtime.close();
        require(terminal.get() == VerseLifecycleCommandService.DuplicateOutcome.QUARANTINED && terminalCount.get() == 1,
                "runtime retirement terminates admitted facade callback once");
        controlledProvider.get().deliver(success(source.id(), 7));
        runtime.tick();
        require(terminalCount.get() == 1 && runtime.listManaged().stream().noneMatch(item -> item.state() == io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing.State.READY),
                "late real bridge completion cannot restore closed facade readiness or callback");
        helper.succeed();
    }

    /** Compiled-only omitted-seed create coverage through the production runtime/gate facade. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeCreateUsesSharedSeedAndQuarantinesUncertainResults(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ControlledCreate normal = new ControlledCreate(level);
        ServerVerseRuntime runtime = controlledCreateRuntime(level, normal, false);
        AtomicInteger terminal = new AtomicInteger();
        require(runtime.beginCreate("normal-owned", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.READY, "normal ready outcome"); terminal.incrementAndGet(); })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "normal create admitted");
        runtime.tick();
        require(normal.creates.get() == 1 && terminal.get() == 1 && runtime.listManaged().stream()
                .anyMatch(entry -> entry.name().equals("dynamicverse:normal-owned") && entry.state()
                        == io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing.State.READY)
                && runtime.recordedEffectiveSeed("dynamicverse:normal-owned").orElseThrow() == level.getSeed(), "normal publishes once with observed seed");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> ready = new AtomicReference<>();
        runtime.beginAcquireReadyRead("normal-owned", ready::set);
        runtime.tick();
        require(ready.get().lease().isPresent() && runtime.resolve(ready.get().lease().orElseThrow()) == level,
                "bare owned name resolves through the existing access facade");
        ready.get().lease().orElseThrow().close();
        require(runtime.beginDuplicate("normal-owned", "normal-copy", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_BUSY,
                "bare owned names reach the duplicate facade rather than minecraft/default lookup");

        ControlledCreate flat = new ControlledCreate(level);
        ServerVerseRuntime flatRuntime = controlledCreateRuntime(level, flat, false);
        require(flatRuntime.beginCreate("flat-owned", VerseLifecycleCommandService.CreateType.SUPERFLAT, java.util.OptionalLong.empty(), ignored -> { })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "flat create admitted");
        flatRuntime.tick();
        require(flat.creates.get() == 1 && flatRuntime.listManaged().stream().anyMatch(entry -> entry.name().equals("dynamicverse:flat-owned")
                && entry.state() == io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing.State.READY), "flat publishes ready");

        ControlledCreate explicit = new ControlledCreate(level);
        explicit.explicitSeeds = false; // capability-absent negative fixture
        ServerVerseRuntime explicitRuntime = controlledCreateRuntime(level, explicit, false);
        require(explicitRuntime.beginCreate("no-seed", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(42L), ignored -> { })
                == VerseLifecycleCommandService.CreateAdmission.ERR_PER_VERSE_SEED_UNSUPPORTED && explicit.creates.get() == 0
                && explicitRuntime.listManaged().isEmpty(), "supplied seed mutates nothing");

        ControlledCreate collision = new ControlledCreate(level); collision.storageExists = true;
        ServerVerseRuntime collisionRuntime = controlledCreateRuntime(level, collision, false);
        require(collisionRuntime.beginCreate("orphan", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                == VerseLifecycleCommandService.CreateAdmission.ERR_VERSE_ALREADY_EXISTS && collision.creates.get() == 0,
                "orphan collision invokes no provider create");

        ControlledCreate mismatch = new ControlledCreate(level);
        ServerVerseRuntime mismatchRuntime = controlledCreateRuntime(level, mismatch, true);
        AtomicInteger quarantined = new AtomicInteger();
        require(mismatchRuntime.beginCreate("mismatch", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.QUARANTINED, "mismatch quarantines"); quarantined.incrementAndGet(); })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "mismatch admitted");
        for (int tick = 0; tick < 20; tick++) mismatchRuntime.tick();
        require(mismatch.creates.get() == 1 && quarantined.get() == 1 && mismatchRuntime.listManaged().stream().anyMatch(entry -> entry.name().equals("dynamicverse:mismatch")
                && entry.state() == io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing.State.QUARANTINED), "mismatch never publishes ready");

        ControlledCreate pending = new ControlledCreate(level); pending.visibleAfterCreate = false;
        ServerVerseRuntime pendingRuntime = controlledCreateRuntime(level, pending, false);
        AtomicInteger pendingTerminal = new AtomicInteger();
        require(pendingRuntime.beginCreate("pending-close", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.QUARANTINED, "pending close quarantines"); pendingTerminal.incrementAndGet(); })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "pending create admitted");
        pendingRuntime.close(); pendingRuntime.tick(); pendingRuntime.close();
        require(pendingTerminal.get() == 1, "pending close terminal exactly once");

        ControlledCreate queued = new ControlledCreate(level); queued.returnNull = true;
        ServerVerseRuntime queuedRuntime = controlledCreateRuntime(level, queued, false);
        AtomicInteger queuedTerminal = new AtomicInteger();
        require(queuedRuntime.beginCreate("queued-close", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.QUARANTINED, "queued close quarantines"); queuedTerminal.incrementAndGet(); })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "queued terminal admitted");
        queuedRuntime.close(); queuedRuntime.tick(); queuedRuntime.close();
        require(queuedTerminal.get() == 1, "queued close terminal exactly once");
        helper.succeed();
    }

    /** Compiled-only actual runtime store-fault boundary; no native provider call is permitted. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeRejectsCreateWhenIntentCannotPersist(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ControlledCreate create = new ControlledCreate(level);
        ManagedVerseStateStore failing = new ManagedVerseStateStore() {
            @Override public State read() { return State.empty(); }
            @Override public void write(State state) { throw new IllegalStateException("controlled durable write failure"); }
        };
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, failing);
        AtomicInteger callbacks = new AtomicInteger();
        require(runtime.beginCreate("write-failure", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> callbacks.incrementAndGet())
                        == VerseLifecycleCommandService.CreateAdmission.ERR_PROVIDER_UNAVAILABLE,
                "create refuses before provider mutation when durable intent write fails");
        require(create.creates.get() == 0 && callbacks.get() == 0 && runtime.listManaged().isEmpty(),
                "no provider call, terminal claim, or READY publication follows failed pre-admission persistence");
        runtime.close();
        helper.succeed();
    }

    /** Compiled-only: a fresh runtime converts an interrupted intent to a durable failure before replaying verified READY source state. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeRecoversPendingIntentBeforeVerifiedSourceReplay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String source = "dynamicverse:interrupted-source";
        String destination = "dynamicverse:interrupted-destination";
        ManagedVerseStateStore.ReadyVerse sourceReady = new ManagedVerseStateStore.ReadyVerse(source, VerseLifecycleCommandService.CreateType.NORMAL,
                level.getSeed(), 13L, "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        ManagedVerseStateStore.ReadyVerse destinationReady = new ManagedVerseStateStore.ReadyVerse(destination, VerseLifecycleCommandService.CreateType.NORMAL,
                level.getSeed(), 0L, "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        java.util.UUID operationId = java.util.UUID.randomUUID();
        ManagedVerseStateStore.OperationIntent pending = new ManagedVerseStateStore.OperationIntent(operationId,
                ManagedVerseStateStore.OperationKind.DUPLICATE, source, destination, true, destinationReady,
                ManagedVerseStateStore.OperationPhase.PENDING, null);
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(source, sourceReady), java.util.Map.of(operationId, pending)));
        ControlledCreate retained = new ControlledCreate(level);
        retained.storedData = true; retained.reloads = true;
        ServerVerseRuntime unavailableSource = replayCreateRuntime(level, retained, false, state);
        for (int tick = 0; tick < 20; tick++) unavailableSource.tick();
        ManagedVerseStateStore.OperationIntent interrupted = state.read().operations().get(operationId);
        require(interrupted != null && interrupted.id().equals(operationId) && interrupted.kind() == ManagedVerseStateStore.OperationKind.DUPLICATE
                        && interrupted.source().equals(source) && interrupted.destination().equals(destination) && interrupted.priorSourceLoaded()
                        && interrupted.phase() == ManagedVerseStateStore.OperationPhase.FAILED && "ERR_OPERATION_INTERRUPTED".equals(interrupted.error()),
                "fresh runtime retains exact interrupted intent as durable failed state");
        require(retained.reloadCalls.get() == 1 && unavailableSource.listManaged().stream().anyMatch(entry -> entry.name().equals(destination)
                        && entry.state() == ManagedVerseListing.State.QUARANTINED) && unavailableSource.listManaged().stream().anyMatch(entry -> entry.name().equals(source)
                        && entry.state() == ManagedVerseListing.State.QUARANTINED),
                "interrupted destination and unmatched retained source remain unavailable");
        unavailableSource.close();
        retained.replayedKeys.add(source);
        ServerVerseRuntime replayedSource = replayCreateRuntime(level, retained, false, state);
        replayedSource.tick();
        require(retained.reloadCalls.get() == 2 && replayedSource.listManaged().stream().anyMatch(entry -> entry.name().equals(source)
                        && entry.state() == ManagedVerseListing.State.READY) && replayedSource.listManaged().stream().anyMatch(entry -> entry.name().equals(destination)
                        && entry.state() == ManagedVerseListing.State.QUARANTINED),
                "only matching retained current evidence republishes source READY after interruption recovery");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> sourceRead = new AtomicReference<>();
        replayedSource.beginAcquireReadyRead("interrupted-source", sourceRead::set); replayedSource.tick();
        io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease sourceLease = sourceRead.get().lease().orElseThrow();
        require(replayedSource.resolve(sourceLease) == level, "verified source replay alone reopens travel");
        sourceLease.close(); replayedSource.close(); helper.succeed();
    }

    /** Compiled-only: a CREATE terminal READY write failure quarantines exactly once while preserving its durable intent. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesOnceWhenCreateTerminalPublicationFails(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InMemoryManagedVerseStateStore backing = new InMemoryManagedVerseStateStore();
        AtomicBoolean failAfterIntent = new AtomicBoolean();
        ManagedVerseStateStore state = new ManagedVerseStateStore() {
            @Override public State read() { return backing.read(); }
            @Override public void write(State next) {
                if (failAfterIntent.get()) throw new IllegalStateException("controlled create terminal publication failure");
                backing.write(next);
            }
        };
        ControlledCreate create = new ControlledCreate(level);
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, state);
        AtomicInteger callbacks = new AtomicInteger();
        require(runtime.beginCreate("create-publication-failure", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                value -> { require(value == VerseLifecycleCommandService.CreateOutcome.QUARANTINED, "create terminal publication failure quarantines"); callbacks.incrementAndGet(); })
                == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "create admits after durable intent write");
        require(create.creates.get() == 1 && backing.read().operations().size() == 1, "create provider call follows durable pending intent");
        failAfterIntent.set(true);
        for (int tick = 0; tick < 20; tick++) runtime.tick();
        require(callbacks.get() == 1 && backing.read().operations().size() == 1 && !backing.read().ready().containsKey("dynamicverse:create-publication-failure"),
                "failed CREATE terminal write retains intent and never persists READY");
        require(runtime.listManaged().stream().anyMatch(entry -> entry.name().equals("dynamicverse:create-publication-failure")
                        && entry.state() == ManagedVerseListing.State.QUARANTINED), "failed CREATE terminal remains conservatively quarantined");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> unavailable = new AtomicReference<>();
        runtime.beginAcquireReadyRead("create-publication-failure", unavailable::set); runtime.tick();
        require(unavailable.get().lease().isEmpty(), "failed CREATE terminal grants no read access");
        require(runtime.beginCreate("create-publication-failure", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ERR_VERSE_ALREADY_EXISTS, "late duplicate create attempt remains refused");
        for (int tick = 0; tick < 20; tick++) runtime.tick();
        runtime.close(); runtime.tick(); runtime.close();
        require(callbacks.get() == 1 && runtime.beginCreate("create-publication-failure", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ERR_PROVIDER_UNAVAILABLE, "close cannot replay terminal feedback or reopen admission");
        helper.succeed();
    }

    /** Compiled-only fresh-runtime replay: storage must exist and current evidence must agree before READY. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeReplaysOnlyVerifiedRetainedReadyState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String key = "dynamicverse:replayed";
        ManagedVerseStateStore.ReadyVerse ready = new ManagedVerseStateStore.ReadyVerse(key, VerseLifecycleCommandService.CreateType.NORMAL,
                level.getSeed(), 9L, "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(key, ready), java.util.Map.of()));
        ControlledCreate retained = new ControlledCreate(level); retained.storedData = true; retained.reloads = true; retained.replayedKeys.add(key);
        ServerVerseRuntime replayed = replayCreateRuntime(level, retained, false, state);
        require(replayed.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.PENDING),
                "reload return is not READY before a later runtime observation tick");
        replayed.tick();
        require(retained.reloadCalls.get() == 1 && replayed.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.READY),
                "fresh runtime publishes only after controlled load/current evidence");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> read = new AtomicReference<>();
        replayed.beginAcquireReadyRead(key, read::set); replayed.tick();
        require(read.get().lease().isPresent() && replayed.resolve(read.get().lease().orElseThrow()) == level, "verified replay alone admits travel");
        read.get().lease().orElseThrow().close();
        ControlledCreate absent = new ControlledCreate(level);
        ServerVerseRuntime missing = controlledCreateRuntime(level, absent, false, state);
        require(absent.reloadCalls.get() == 0 && missing.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "missing path refuses provider load and stays unavailable");
        ControlledCreate nullLoad = new ControlledCreate(level); nullLoad.storedData = true; nullLoad.replayedKeys.add(key);
        ServerVerseRuntime nullRuntime = controlledCreateRuntime(level, nullLoad, false, state);
        for (int tick = 0; tick < 20; tick++) nullRuntime.tick();
        require(nullLoad.reloadCalls.get() == 1 && nullRuntime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "null provider load has one attempt but never READY");
        ControlledCreate mismatched = new ControlledCreate(level); mismatched.storedData = true; mismatched.reloads = true; mismatched.replayedKeys.add(key);
        ServerVerseRuntime wrongRuntime = controlledCreateRuntime(level, mismatched, true, state);
        for (int tick = 0; tick < 20; tick++) wrongRuntime.tick();
        require(mismatched.reloadCalls.get() == 1 && wrongRuntime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "mismatched current seed never becomes READY");
        replayed.close(); missing.close(); nullRuntime.close(); wrongRuntime.close();
        helper.succeed();
    }

    /** Compiled-only: contradictory retained provenance is quarantined before storage or provider reload. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeRejectsContradictorySeedBeforeStorageOrReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); String key = "dynamicverse:contradictory-seed";
        ManagedVerseStateStore.ReadyVerse contradictory = new ManagedVerseStateStore.ReadyVerse(key,
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(11L), 12L, 3L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(key, contradictory), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.storedData = true; create.reloads = true;
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, state);
        require(create.storageCalls.get() == 0 && create.reloadCalls.get() == 0,
                "contradictory provenance is rejected before storage or reload provider boundaries");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "contradictory retained READY remains quarantined");
        runtime.close(); helper.succeed();
    }

    /** Compiled-only: a present capability persists requested/effective seed before later READY publication. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeCreatesExplicitSeedOnlyThroughPresentCapability(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); long seed = 987654321L; String key = "dynamicverse:seeded-created";
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        ControlledCreate create = new ControlledCreate(level);
        AtomicBoolean visible = new AtomicBoolean(); AtomicInteger callbacks = new AtomicInteger();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor ->
                        visible.get() && create.createdKeys.contains(descriptor.id())
                                ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), key + "@live", seed) : null,
                (catalog, gate) -> gatewayForReplay(gate), create, state);
        require(runtime.beginCreate("seeded-created", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(seed),
                        outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.READY, "seeded create terminal"); callbacks.incrementAndGet(); })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "present capability admits explicit seed");
        ManagedVerseStateStore.OperationIntent intent = state.read().operations().values().stream().findFirst().orElseThrow();
        require(intent.destinationDescriptor().requestedSeed().equals(java.util.OptionalLong.of(seed))
                        && intent.destinationDescriptor().effectiveSeed() == seed && create.seededCreateCalls.get() == 1
                        && create.lastRequestedCreateSeed == seed && create.seededDescriptor == create.created
                        && create.seededDescriptor.key().location().equals(ResourceLocation.parse(key))
                        && create.seededDescriptor.generator() == create.created.generator()
                        && create.seededDescriptor.type() == create.created.type()
                        && create.seededDescriptor.properties() == create.created.properties()
                        && create.creates.get() == 1,
                "seeded create dispatches the exact id, generator, type, properties, and requested seed");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.PENDING),
                "create return cannot publish READY before current observation");
        visible.set(true); runtime.tick();
        ManagedVerseStateStore.ReadyVerse ready = state.read().ready().get(key);
        require(ready.requestedSeed().equals(java.util.OptionalLong.of(seed)) && ready.effectiveSeed() == seed
                        && state.read().operations().isEmpty() && callbacks.get() == 1,
                "later exact observation atomically retains READY seed provenance and settles once");
        runtime.close(); helper.succeed();
    }

    /** Compiled-only: an unavailable capability refuses supplied seed before any owned mutation. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeRefusesExplicitSeedWhenCapabilityIsUnavailable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); ControlledCreate create = new ControlledCreate(level); create.explicitSeeds = false;
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore(); AtomicInteger callbacks = new AtomicInteger();
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, state);
        require(runtime.beginCreate("seeded-unavailable", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(63L),
                        ignored -> callbacks.incrementAndGet()) == VerseLifecycleCommandService.CreateAdmission.ERR_PER_VERSE_SEED_UNSUPPORTED,
                "missing capability refuses supplied seed before admission");
        require(create.creates.get() == 0 && create.seededCreateCalls.get() == 0 && create.storageCalls.get() == 0
                        && state.read().ready().isEmpty() && state.read().operations().isEmpty() && callbacks.get() == 0,
                "missing capability leaves provider, catalog state, intent, and callback untouched");
        runtime.close(); helper.succeed();
    }

    /** Compiled-only: deferred provider linkage failure is quarantined once with no legacy fallback. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesExplicitSeedLinkageFailureOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); ControlledCreate create = new ControlledCreate(level); create.throwSeededCreateLinkage = true;
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore(); AtomicInteger callbacks = new AtomicInteger();
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, state);
        require(runtime.beginCreate("seeded-linkage", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(64L),
                        outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.QUARANTINED, "linkage terminal"); callbacks.incrementAndGet(); })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "deferred linkage failure remains one admitted terminal");
        runtime.tick(); runtime.tick();
        require(create.seededCreateCalls.get() == 1 && create.creates.get() == 0 && callbacks.get() == 1
                        && state.read().ready().isEmpty() && state.read().operations().size() == 1,
                "linkage failure has no shared-seed retry, no READY publication, and exactly one retained uncertain intent");
        runtime.close(); runtime.tick();
        require(callbacks.get() == 1, "close cannot replay the linkage-failure terminal");
        helper.succeed();
    }

    /** Compiled-only: a retained nondefault seed reaches the provider seed overload and publishes only after later observation. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeReplaysRetainedSeedOnlyAfterLaterRegistration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); String key = "dynamicverse:seeded-replayed"; long seed = 4242424242L;
        ManagedVerseStateStore.ReadyVerse ready = new ManagedVerseStateStore.ReadyVerse(key,
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(seed), seed, 41L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(key, ready), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.storedData = true; create.reloads = true; create.replayedKeys.add(key);
        AtomicBoolean visible = new AtomicBoolean();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor ->
                        visible.get() && create.replayedKeys.contains(descriptor.id())
                                ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), "seeded-replayed@live", seed)
                                : null,
                (catalog, gate) -> gatewayForReplay(gate), create, state);
        require(create.storageCalls.get() == 1 && create.reloadCalls.get() == 1 && create.seededReloadCalls.get() == 1
                        && create.lastRequestedReloadSeed == seed,
                "retained requested seed reaches exactly one seed-bearing provider reload");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.PENDING),
                "reload return is excluded from READY/list access until later registration");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> before = new AtomicReference<>();
        runtime.beginAcquireReadyRead(key, before::set); runtime.tick();
        require(before.get().lease().isEmpty(), "travel stays unavailable before matching later observation");
        visible.set(true); runtime.tick();
        ManagedVerseStateStore.ReadyVerse durable = state.read().ready().get(key);
        require(durable.requestedSeed().equals(java.util.OptionalLong.of(seed)) && durable.effectiveSeed() == seed && durable.generation() == 41L,
                "requested/effective seed and generation survive durable replay publication");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> after = new AtomicReference<>();
        runtime.beginAcquireReadyRead(key, after::set); runtime.tick();
        io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease lease = after.get().lease().orElseThrow();
        require(runtime.resolve(lease) == level && runtime.recordedEffectiveSeed(key).orElseThrow() == seed
                        && lease.generation() == 41L && runtime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.READY),
                "fresh runtime registers exact seed identity and admits a resolving read lease only after observation");
        lease.close(); runtime.close(); helper.succeed();
    }

    /** Compiled-only: a missing optional capability quarantines retained explicit seed before storage or reload. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesRetainedSeedWithoutCapabilityBeforeStorage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); String key = "dynamicverse:seeded-replay-unavailable"; long seed = 91L;
        ManagedVerseStateStore.ReadyVerse ready = new ManagedVerseStateStore.ReadyVerse(key,
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(seed), seed, 43L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(key, ready), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.explicitSeeds = false; create.storedData = true; create.reloads = true;
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, state);
        require(create.storageCalls.get() == 0 && create.reloadCalls.get() == 0 && create.seededReloadCalls.get() == 0,
                "missing capability quarantines retained seed before storage probe or either reload overload");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "retained explicit seed remains unavailable when capability is absent");
        runtime.close(); helper.succeed();
    }

    /** Compiled-only: deferred explicit-seed reload linkage failure has one fail-closed replay outcome. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesSeededReloadLinkageFailureOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); String key = "dynamicverse:seeded-reload-linkage"; long seed = 92L;
        ManagedVerseStateStore.ReadyVerse ready = new ManagedVerseStateStore.ReadyVerse(key,
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(seed), seed, 44L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(key, ready), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.storedData = true; create.reloads = true; create.throwSeededReloadLinkage = true;
        ServerVerseRuntime runtime = controlledCreateRuntime(level, create, false, state);
        runtime.tick(); runtime.tick();
        require(create.storageCalls.get() == 1 && create.seededReloadCalls.get() == 1 && create.reloadCalls.get() == 0,
                "deferred seed reload linkage failure has one operation and no shared-seed fallback or replay");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(key) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "linkage-failed retained replay settles as a single quarantine outcome");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> read = new AtomicReference<>();
        runtime.beginAcquireReadyRead(key, read::set); runtime.tick();
        require(read.get().lease().isEmpty(), "linkage-failed replay cannot admit a read lease");
        runtime.close(); helper.succeed();
    }

    /** Compiled-only: one throwing replay lookup quarantines only that retained entry and cannot abort the owned tick. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesThrowingReplayLookupWithoutAbortingOtherTickWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); long seed = level.getSeed();
        String throwingKey = "dynamicverse:throwing-replay";
        String goodKey = "dynamicverse:other-replay";
        ManagedVerseStateStore.ReadyVerse throwing = new ManagedVerseStateStore.ReadyVerse(throwingKey,
                VerseLifecycleCommandService.CreateType.NORMAL, seed, 12L, "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        ManagedVerseStateStore.ReadyVerse good = new ManagedVerseStateStore.ReadyVerse(goodKey,
                VerseLifecycleCommandService.CreateType.NORMAL, seed, 13L, "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(throwingKey, throwing, goodKey, good), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.storedData = true; create.reloads = true;
        create.replayedKeys.add(throwingKey); create.replayedKeys.add(goodKey);
        AtomicInteger createCallbacks = new AtomicInteger();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor -> {
            if (descriptor.id().equals(throwingKey)) throw new IllegalStateException("controlled replay lookup failure");
            if (create.replayedKeys.contains(descriptor.id()) || create.createdKeys.contains(descriptor.id())) {
                return new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), descriptor.id() + "@live", seed);
            }
            return null;
        }, (catalog, gate) -> gatewayForReplay(gate), create, state);
        require(create.reloadCalls.get() == 2 && create.seededReloadCalls.get() == 0,
                "each retained legacy READY entry gets only its constructor replay load");
        require(runtime.beginCreate("unrelated-tick-create", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                        outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.READY, "unrelated create terminal"); createCallbacks.incrementAndGet(); })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "unrelated create admitted before replay observation");
        runtime.tick();
        require(createCallbacks.get() == 1, "throwing replay lookup cannot abort unrelated terminal-feedback processing");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(throwingKey) && e.state() == ManagedVerseListing.State.QUARANTINED),
                "throwing replay lookup settles only its retained entry as quarantined");
        require(runtime.listManaged().stream().anyMatch(e -> e.name().equals(goodKey) && e.state() == ManagedVerseListing.State.READY),
                "unrelated retained replay continues through the same owned tick");
        runtime.tick();
        require(create.reloadCalls.get() == 2, "quarantined throwing replay is removed and never reloads again");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> goodRead = new AtomicReference<>();
        runtime.beginAcquireReadyRead(goodKey, goodRead::set); runtime.tick();
        io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease lease = goodRead.get().lease().orElseThrow();
        require(runtime.resolve(lease) == level, "unrelated replay still resolves a normal read lease");
        lease.close(); runtime.close(); helper.succeed();
    }

    /** Compiled-only: a throwing create lookup retires its reservation once without aborting later owned work. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesThrowingCreateLookupWithoutAbortingTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); long seed = level.getSeed();
        String bad = "dynamicverse:throwing-create"; String good = "dynamicverse:other-create"; String replay = "dynamicverse:other-replay";
        ManagedVerseStateStore.ReadyVerse retained = new ManagedVerseStateStore.ReadyVerse(replay,
                VerseLifecycleCommandService.CreateType.NORMAL, seed, 71L, "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(replay, retained), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.storedData = true; create.reloads = true; create.replayedKeys.add(replay);
        AtomicBoolean armed = new AtomicBoolean(); AtomicInteger badLookups = new AtomicInteger(); AtomicInteger badCallbacks = new AtomicInteger(); AtomicInteger goodCallbacks = new AtomicInteger();
        AtomicReference<VerseOperationGate> observedGate = new AtomicReference<>();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor -> {
            if (descriptor.id().equals(bad) && armed.get()) { badLookups.incrementAndGet(); throw new IllegalStateException("controlled create lookup failure"); }
            if (create.createdKeys.contains(descriptor.id()) || create.replayedKeys.contains(descriptor.id())) {
                return new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), descriptor.id() + "@live", seed);
            }
            return null;
        }, (catalog, gate) -> { observedGate.set(gate); return gatewayForReplay(gate); }, create, state);
        require(runtime.beginCreate("throwing-create", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                        outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.QUARANTINED, "throwing create terminal"); badCallbacks.incrementAndGet(); })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "throwing create admitted once");
        require(runtime.beginCreate("other-create", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(),
                        outcome -> { require(outcome == VerseLifecycleCommandService.CreateOutcome.READY, "unrelated create terminal"); goodCallbacks.incrementAndGet(); })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "unrelated create admitted");
        require(badLookups.get() == 0 && badCallbacks.get() == 0 && goodCallbacks.get() == 0,
                "the fault remains disarmed during admission: no lookup or terminal callback occurs before observation");
        armed.set(true);
        runtime.tick();
        require(badLookups.get() == 1 && badCallbacks.get() == 1 && goodCallbacks.get() == 1,
                "throwing create lookup settles once while unrelated terminal feedback still runs");
        require(observedGate.get().readiness(bad).state() == VerseOperationGate.Readiness.State.QUARANTINED
                        && runtime.listManaged().stream().anyMatch(e -> e.name().equals(bad) && e.state() == ManagedVerseListing.State.QUARANTINED)
                        && runtime.listManaged().stream().anyMatch(e -> e.name().equals(good) && e.state() == ManagedVerseListing.State.READY)
                        && runtime.listManaged().stream().anyMatch(e -> e.name().equals(replay) && e.state() == ManagedVerseListing.State.READY),
                "CreateReservation.close releases operation ownership then leaves failed create quarantined, while unrelated create/replay become READY");
        boolean quarantinedProbe = false;
        try (VerseOperationGate.CreateReservation ignored = observedGate.get().beginCreate(bad)) { }
        catch (IllegalStateException expected) { quarantinedProbe = true; }
        require(quarantinedProbe, "same-key gate probe is refused by the retained quarantine, not published READY state");
        runtime.tick(); runtime.close();
        require(badLookups.get() == 1 && badCallbacks.get() == 1 && create.reloadCalls.get() == 1,
                "failed create is removed without lookup retry or reload, including close");
        helper.succeed();
    }

    /** Compiled-only: an explicitly seeded replay source refuses duplicate before reservation or bridge/provider work. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeRefusesSeededDuplicateBeforeAnyBridgeWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel(); String source = "dynamicverse:seeded-source";
        ManagedVerseStateStore.ReadyVerse seeded = new ManagedVerseStateStore.ReadyVerse(source,
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(level.getSeed()), level.getSeed(), 5L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        state.write(new ManagedVerseStateStore.State(java.util.Map.of(source, seeded), java.util.Map.of()));
        ControlledCreate create = new ControlledCreate(level); create.storedData = true; create.reloads = true; create.replayedKeys.add(source);
        AtomicInteger bridgeAdmissions = new AtomicInteger(); AtomicInteger callbacks = new AtomicInteger();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor -> create.replayedKeys.contains(descriptor.id())
                        ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), "seeded", level.getSeed()) : null,
                (catalog, gate) -> new ServerVerseRuntime.BridgeGateway() {
                    @Override public void registerReady(String key, String identity, long generation) { gate.publishReady(key, identity, generation); }
                    @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String s, String d, java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> done) {
                        bridgeAdmissions.incrementAndGet(); return ProviderDuplicationBridge.BridgeAdmission.REJECTED;
                    }
                }, create, state);
        runtime.tick();
        ManagedVerseStateStore.State before = state.read();
        require(runtime.beginDuplicate(source, "seeded-destination", ignored -> callbacks.incrementAndGet())
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_PER_VERSE_SEED_UNSUPPORTED,
                "seeded source duplicate is refused before destination reservation");
        require(bridgeAdmissions.get() == 0 && callbacks.get() == 0 && state.read().equals(before)
                        && runtime.listManaged().stream().noneMatch(e -> e.name().equals("dynamicverse:seeded-destination")),
                "refusal writes no intent, calls no bridge, and fabricates no destination");
        runtime.close(); helper.succeed();
    }

    /** Compiled-only: source provenance comes from beginCreate, then the real bridge publishes LOADED. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeDuplicatePersistsLoadedDestinationAndReplaysIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        ControlledCreate create = new ControlledCreate(level);
        create.visibleAfterCreate = true;
        AtomicReference<ControlledProvider> provider = new AtomicReference<>();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor ->
                        (create.createdKeys.contains(descriptor.id()) || create.replayedKeys.contains(descriptor.id()))
                                ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), descriptor.id().endsWith("dup-destination") ? "destination" : "restored", level.getSeed()) : null,
                (catalog, gate) -> {
                    ControlledProvider port = new ControlledProvider(gate, "dynamicverse:dup-source", "dynamicverse:dup-destination"); provider.set(port);
                    ProviderDuplicationBridge bridge = new ProviderDuplicationBridge(catalog, gate, port,
                            intent -> new ClosedDimensionCopyRequest(Path.of("/tmp"), Path.of("dup-source"), Path.of("dup-destination"), intent.sourceKey(), intent.generation()),
                            (request, receipt, fence) -> { });
                    return new ServerVerseRuntime.BridgeGateway() {
                        @Override public void registerReady(String k, String i, long g) throws ClosedDimensionCopyException { bridge.registerReady(k, i, g); }
                        @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String s, String d, java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> done) throws ClosedDimensionCopyException { return bridge.beginDuplicate(s, d, done); }
                    };
                }, create, state);
        require(runtime.beginCreate("dup-source", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { }) == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "source create admitted");
        runtime.tick();
        provider.get().onDestinationLoad = () -> create.createdKeys.add("dynamicverse:dup-destination");
        AtomicInteger callbacks = new AtomicInteger();
        require(runtime.beginDuplicate("dup-source", "dup-destination", outcome -> { require(outcome == VerseLifecycleCommandService.DuplicateOutcome.LOADED, "loaded terminal"); callbacks.incrementAndGet(); }) == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED, "duplicate admitted");
        provider.get().deliver(success("dynamicverse:dup-source", 7)); runtime.tick();
        require(callbacks.get() == 1 && state.read().ready().containsKey("dynamicverse:dup-destination") && state.read().operations().isEmpty(), "loaded destination and intent transition are durable once");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> liveRead = new AtomicReference<>();
        runtime.beginAcquireReadyRead("dup-destination", liveRead::set); runtime.tick();
        io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease liveLease = liveRead.get().lease().orElseThrow();
        ManagedVerseStateStore.ReadyVerse durableDestination = state.read().ready().get("dynamicverse:dup-destination");
        ManagedVerseListing liveListing = runtime.listManaged().stream().filter(entry -> entry.name().equals("dynamicverse:dup-destination")).findFirst().orElseThrow();
        require(runtime.resolve(liveLease) == level && runtime.recordedEffectiveSeed("dynamicverse:dup-destination").orElseThrow() == level.getSeed(),
                "live duplicate lease resolves the observed destination and retains its shared seed");
        require(liveLease.generation() == 7 && liveListing.generation().orElseThrow() == liveLease.generation()
                        && durableDestination.generation() == liveLease.generation() && durableDestination.effectiveSeed() == level.getSeed(),
                "live lease, listing, and durable READY agree with the observed destination generation");
        liveLease.close();
        create.storedData = true; create.reloads = true; create.replayedKeys.add("dynamicverse:dup-destination");
        ServerVerseRuntime fresh = ServerVerseRuntime.controlled(level.getServer(), descriptor -> create.replayedKeys.contains(descriptor.id()) ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(), descriptor.id()+"@live", level.getSeed()) : null,
                (catalog, gate) -> gatewayForReplay(gate), create, state);
        fresh.tick();
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> read = new AtomicReference<>(); fresh.beginAcquireReadyRead("dup-destination", read::set); fresh.tick();
        io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease replayLease = read.get().lease().orElseThrow();
        ManagedVerseStateStore.ReadyVerse replayedDestination = state.read().ready().get("dynamicverse:dup-destination");
        require(fresh.resolve(replayLease) == level && fresh.recordedEffectiveSeed("dynamicverse:dup-destination").orElseThrow() == level.getSeed(),
                "fresh runtime resolves its replay lease against the retained current destination");
        require(replayLease.generation() == replayedDestination.generation() && replayedDestination.effectiveSeed() == level.getSeed(),
                "fresh replay lease agrees with the persisted READY seed and generation");
        replayLease.close(); runtime.close(); fresh.close(); helper.succeed();
    }

    /** Compiled-only: Runtime keeps the duplicate intent and quarantines when its terminal READY write fails. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeQuarantinesOnceWhenDuplicateTerminalPublicationFails(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InMemoryManagedVerseStateStore backing = new InMemoryManagedVerseStateStore();
        AtomicBoolean failTerminalWrite = new AtomicBoolean();
        ManagedVerseStateStore state = new ManagedVerseStateStore() {
            @Override public State read() { return backing.read(); }
            @Override public void write(State next) {
                if (failTerminalWrite.get()) throw new IllegalStateException("controlled duplicate terminal publication failure");
                backing.write(next);
            }
        };
        ControlledCreate create = new ControlledCreate(level);
        AtomicReference<ControlledProvider> provider = new AtomicReference<>();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor ->
                        (create.createdKeys.contains(descriptor.id()) || create.replayedKeys.contains(descriptor.id()))
                                ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(),
                                descriptor.id().endsWith("publish-failure-destination") ? "destination" : "restored", level.getSeed()) : null,
                (catalog, gate) -> {
                    ControlledProvider port = new ControlledProvider(gate, "dynamicverse:publish-failure-source", "dynamicverse:publish-failure-destination");
                    provider.set(port);
                    ProviderDuplicationBridge bridge = new ProviderDuplicationBridge(catalog, gate, port,
                            intent -> new ClosedDimensionCopyRequest(Path.of("/tmp"), Path.of("publish-failure-source"), Path.of("publish-failure-destination"), intent.sourceKey(), intent.generation()),
                            (request, receipt, fence) -> { });
                    return new ServerVerseRuntime.BridgeGateway() {
                        @Override public void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException { bridge.registerReady(key, identity, generation); }
                        @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination,
                                                                                                   java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException {
                            return bridge.beginDuplicate(source, destination, completion);
                        }
                    };
                }, create, state);
        require(runtime.beginCreate("publish-failure-source", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "source create admitted");
        runtime.tick();
        provider.get().onDestinationLoad = () -> create.createdKeys.add("dynamicverse:publish-failure-destination");
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<VerseLifecycleCommandService.DuplicateOutcome> outcome = new AtomicReference<>();
        require(runtime.beginDuplicate("publish-failure-source", "publish-failure-destination", value -> { outcome.set(value); callbacks.incrementAndGet(); })
                        == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED, "duplicate admission persists its intent first");
        require(backing.read().operations().size() == 1, "pending duplicate intent is durable before terminal");
        failTerminalWrite.set(true);
        provider.get().deliver(success("dynamicverse:publish-failure-source", 7)); runtime.tick();
        require(outcome.get() == VerseLifecycleCommandService.DuplicateOutcome.QUARANTINED && callbacks.get() == 1,
                "failed terminal publication delivers one later-tick quarantine result");
        require(backing.read().operations().size() == 1 && !backing.read().ready().containsKey("dynamicverse:publish-failure-destination"),
                "failed terminal publication retains intent and never persists destination READY");
        AtomicReference<io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot> unavailable = new AtomicReference<>();
        runtime.beginAcquireReadyRead("publish-failure-destination", unavailable::set); runtime.tick();
        require(unavailable.get().lease().isEmpty() && runtime.listManaged().stream().anyMatch(entry -> entry.name().equals("dynamicverse:publish-failure-destination")
                        && entry.state() == ManagedVerseListing.State.QUARANTINED), "quarantined destination has no live read lease or resolve path");
        provider.get().deliver(success("dynamicverse:publish-failure-source", 7)); runtime.tick();
        runtime.close(); provider.get().deliver(success("dynamicverse:publish-failure-source", 7)); runtime.tick(); runtime.close();
        require(callbacks.get() == 1 && outcome.get() == VerseLifecycleCommandService.DuplicateOutcome.QUARANTINED,
                "replayed terminals and retirement cannot replay the quarantined result");
        helper.succeed();
    }

    /** Compiled-only: a pre-staged custom descriptor can never receive copied source provenance. */
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledRuntimeRejectsStagedNondefaultDuplicateDestination(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        ControlledCreate create = new ControlledCreate(level);
        OwnedDimensionDescriptor staged = nondefaultDescriptor("staged-destination", level);
        AtomicReference<ControlledProvider> provider = new AtomicReference<>();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor ->
                        (create.createdKeys.contains(descriptor.id()) || descriptor == staged)
                                ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(),
                                descriptor == staged ? "staged" : "source", level.getSeed()) : null,
                (catalog, gate) -> {
                    ControlledProvider port = new ControlledProvider(gate, "dynamicverse:staged-source", staged.id());
                    provider.set(port);
                    ProviderDuplicationBridge bridge = new ProviderDuplicationBridge(catalog, gate, port,
                            intent -> new ClosedDimensionCopyRequest(Path.of("/tmp"), Path.of("staged-source"), Path.of("staged-destination"), intent.sourceKey(), intent.generation()),
                            (request, receipt, fence) -> { });
                    return new ServerVerseRuntime.BridgeGateway() {
                        @Override public void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException { bridge.registerReady(key, identity, generation); }
                        @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination,
                                                                                                   java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException {
                            return bridge.beginDuplicate(source, destination, completion);
                        }
                    };
                }, create, state);
        require(runtime.beginCreate("staged-source", VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "source create admitted");
        runtime.tick();
        runtime.stageOwnedDestination(staged);
        ManagedVerseStateStore.State before = state.read();
        require(runtime.beginDuplicate("staged-source", "staged-destination", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_ALREADY_EXISTS, "staged destination rejected before bridge admission");
        require(provider.get().admissions.get() == 0 && provider.get().loads.get() == 0 && provider.get().destinationLoads.get() == 0,
                "staged destination invokes no controlled provider operation");
        require(state.read().equals(before) && !state.read().ready().containsKey(staged.id()) && state.read().operations().isEmpty(),
                "staged destination creates no READY record or operation intent");
        require(runtime.listManaged().stream().anyMatch(entry -> entry.name().equals(staged.id()) && entry.state() == ManagedVerseListing.State.PENDING),
                "staged descriptor remains owned but not ready");
        boolean provenanceRejected = false;
        try { runtime.enrollVerifiedReady(new ServerVerseRuntime.EnrollmentProvenance(staged, level, 7, "staged")); }
        catch (IllegalArgumentException | ClosedDimensionCopyException expected) { provenanceRejected = true; }
        require(provenanceRejected && state.read().equals(before), "staged nondefault descriptor was not overwritten with copied provenance");
        runtime.close(); helper.succeed();
    }

    private static ServerVerseRuntime.BridgeGateway gatewayForReplay(VerseOperationGate gate) { return new ServerVerseRuntime.BridgeGateway() {
        @Override public void registerReady(String k, String i, long g) { gate.publishReady(k, i, g); }
        @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String s, String d, java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> done) { return ProviderDuplicationBridge.BridgeAdmission.REJECTED; }
    }; }

    private static OwnedDimensionDescriptor nondefaultDescriptor(String path, ServerLevel level) {
        return new OwnedDimensionDescriptor(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", path)),
                level.getChunkSource().getGenerator(), level.dimensionType(), new DynamicDimensionProperties() {
                    public int priority() { return 99; } public Vector3f baseGravity() { return new Vector3f(1, 2, 3); }
                    public double basePressure() { return 2; } public float universalDrag() { return 3; } public Vector3f magneticNorth() { return new Vector3f(4, 5, 6); }
                });
    }

    private static ServerVerseRuntime controlledCreateRuntime(ServerLevel level, ControlledCreate create, boolean wrongSeed) {
        return controlledCreateRuntime(level, create, wrongSeed, new InMemoryManagedVerseStateStore());
    }
    private static ServerVerseRuntime controlledCreateRuntime(ServerLevel level, ControlledCreate create, boolean wrongSeed, ManagedVerseStateStore state) {
        return ServerVerseRuntime.controlled(level.getServer(), descriptor -> (create.visible && create.createdKeys.contains(descriptor.id()) || create.replayedKeys.contains(descriptor.id())) ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level,
                        descriptor.generator(), descriptor.type(), descriptor.id() + "@" + Integer.toHexString(System.identityHashCode(level)),
                        wrongSeed ? level.getSeed() + 1 : level.getSeed()) : null,
                (catalog, gate) -> new ServerVerseRuntime.BridgeGateway() {
                    @Override public void registerReady(String key, String identity, long generation) { throw new AssertionError("create must publish through its reservation"); }
                    @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination,
                                                                                               java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) {
                        return ProviderDuplicationBridge.BridgeAdmission.REJECTED;
                    }
                }, create, state);
    }
    /** Replay must publish through the bridge/gate; create-only fixtures retain their reservation assertion. */
    private static ServerVerseRuntime replayCreateRuntime(ServerLevel level, ControlledCreate create, boolean wrongSeed, ManagedVerseStateStore state) {
        return ServerVerseRuntime.controlled(level.getServer(), descriptor -> (create.visible && create.createdKeys.contains(descriptor.id()) || create.replayedKeys.contains(descriptor.id())) ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level,
                        descriptor.generator(), descriptor.type(), descriptor.id() + "@" + Integer.toHexString(System.identityHashCode(level)),
                        wrongSeed ? level.getSeed() + 1 : level.getSeed()) : null,
                (catalog, gate) -> gatewayForReplay(gate), create, state);
    }

    private static Scenario admittedScenario(ServerLevel level) throws ClosedDimensionCopyException {
        Scenario scenario = scenario(level);
        scenario.bridge.registerReady(scenario.source.id(), "old", 1);
        require(scenario.bridge.beginDuplicate(scenario.source.id(), scenario.destination.id(), scenario.completion())
                == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "admission");
        return scenario;
    }

    private static Scenario scenario(ServerLevel level) {
        OwnedDimensionCatalog catalog = new OwnedDimensionCatalog();
        OwnedDimensionDescriptor source = descriptor("source", level);
        OwnedDimensionDescriptor destination = descriptor("destination", level);
        catalog.register(source);
        catalog.register(destination);
        VerseOperationGate gate = new VerseOperationGate();
        ControlledProvider provider = new ControlledProvider(gate, source.id(), destination.id());
        AtomicInteger copies = new AtomicInteger();
        AtomicReference<ProviderDuplicationBridge.BridgeOutcome> outcome = new AtomicReference<>();
        AtomicInteger completions = new AtomicInteger();
        Scenario scenario = new Scenario(source, destination, gate, provider, copies, outcome, completions);
        scenario.bridge = new ProviderDuplicationBridge(catalog, gate, provider,
                intent -> new ClosedDimensionCopyRequest(Path.of("/tmp"), Path.of("source"), Path.of("destination"), intent.sourceKey(), intent.generation()),
                (request, receipt, fence) -> {
                    require(gate.tryAcquireReadyRead(source.id()) == null, "source read released before copy");
                    copies.incrementAndGet();
                    if (scenario.throwCopy) throw new IllegalStateException("controlled copy failure");
                });
        return scenario;
    }

    private static ProviderQuiesceCloseTerminal success(String sourceKey, long generation) {
        return new ProviderQuiesceCloseTerminal(sourceKey, generation, true, ClosedDimensionStatus.SAVED_AND_CLOSED, false);
    }

    private static void assertQuarantinedOnce(Scenario scenario, String detail, int expectedCopies, int expectedLoads) {
        require(scenario.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.QUARANTINED, detail + " outcome");
        require(scenario.copies.get() == expectedCopies && scenario.provider.loads.get() == expectedLoads
                && scenario.completions.get() == 1, detail + " exact terminal counts");
        require(scenario.gate.tryAcquireReadyRead(scenario.source.id()) == null, detail + " source must quarantine");
        require(scenario.gate.tryAcquireReadyRead(scenario.destination.id()) == null, detail + " destination must quarantine");
    }

    private static void assertSourceOnlyRestored(Scenario scenario, String detail, int expectedDestinationLoads) {
        require(scenario.outcome.get() == ProviderDuplicationBridge.BridgeOutcome.RESTORED, detail + " outcome");
        require(scenario.provider.destinationLoads.get() == expectedDestinationLoads && scenario.completions.get() == 1,
                detail + " destination completion count");
        assertReady(scenario.gate, scenario.source.id(), "restored", 7);
        require(scenario.gate.tryAcquireReadyRead(scenario.destination.id()) == null, detail + " destination unavailable");
    }

    private static void assertReady(VerseOperationGate gate, String key, String identity, long generation) {
        VerseOperationGate.ReadyRead read = gate.tryAcquireReadyRead(key);
        require(read != null && read.identity().equals(identity) && read.generation() == generation, "ready identity/generation");
        read.close();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static OwnedDimensionDescriptor descriptor(String path, ServerLevel level) {
        return new OwnedDimensionDescriptor(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", path)),
                level.getChunkSource().getGenerator(), level.dimensionType(), new DynamicDimensionProperties() {
                    public int priority() { return 0; } public Vector3f baseGravity() { return new Vector3f(); }
                    public double basePressure() { return 0; } public float universalDrag() { return 0; } public Vector3f magneticNorth() { return new Vector3f(); }
                });
    }

    private static final class Scenario {
        private final OwnedDimensionDescriptor source;
        private final OwnedDimensionDescriptor destination;
        private final VerseOperationGate gate;
        private final ControlledProvider provider;
        private final AtomicInteger copies;
        private final AtomicReference<ProviderDuplicationBridge.BridgeOutcome> outcome;
        private final AtomicInteger completions;
        private ProviderDuplicationBridge bridge;
        private boolean throwCopy;
        private boolean throwCompletion;

        private Scenario(OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination, VerseOperationGate gate, ControlledProvider provider,
                         AtomicInteger copies, AtomicReference<ProviderDuplicationBridge.BridgeOutcome> outcome, AtomicInteger completions) {
            this.source = source; this.destination = destination; this.gate = gate; this.provider = provider;
            this.copies = copies; this.outcome = outcome; this.completions = completions;
        }

        private java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion() {
            return value -> { outcome.set(value); completions.incrementAndGet(); if (throwCompletion) throw new IllegalStateException("controlled completion failure"); };
        }
    }

    private static final class ControlledCreate implements ServerVerseRuntime.CreateGateway {
        private final ServerLevel level;
        private final AtomicInteger creates = new AtomicInteger();
        private OwnedDimensionDescriptor created;
        private final java.util.Set<String> createdKeys = new java.util.HashSet<>();
        private final java.util.Set<String> replayedKeys = new java.util.HashSet<>();
        private boolean storageExists;
        private boolean visible;
        private boolean visibleAfterCreate = true;
        private boolean returnNull;
        private boolean storedData;
        private boolean reloads;
        private final AtomicInteger reloadCalls = new AtomicInteger();
        private final AtomicInteger seededCreateCalls = new AtomicInteger();
        private final AtomicInteger seededReloadCalls = new AtomicInteger();
        private boolean explicitSeeds = true;
        private boolean throwSeededCreateLinkage;
        private boolean throwSeededReloadLinkage;
        private OwnedDimensionDescriptor seededDescriptor;
        private long lastRequestedCreateSeed = Long.MIN_VALUE;
        private long lastRequestedReloadSeed = Long.MIN_VALUE;
        private final AtomicInteger storageCalls = new AtomicInteger();
        private ControlledCreate(ServerLevel level) { this.level = level; }
        @Override public boolean canCreate(ResourceLocation id) { return true; }
        @Override public boolean storageExists(OwnedDimensionDescriptor descriptor) { return storageExists; }
        @Override public ServerLevel create(OwnedDimensionDescriptor descriptor) {
            creates.incrementAndGet();
            created = descriptor;
            createdKeys.add(descriptor.id());
            visible = visibleAfterCreate;
            return returnNull ? null : level;
        }
        @Override public boolean supportsExplicitSeeds() { return explicitSeeds; }
        @Override public ServerLevel create(OwnedDimensionDescriptor descriptor, long requestedSeed) {
            seededCreateCalls.incrementAndGet(); lastRequestedCreateSeed = requestedSeed; seededDescriptor = descriptor;
            if (throwSeededCreateLinkage) throw new NoSuchMethodError("controlled explicit seed linkage failure");
            return create(descriptor);
        }
        @Override public boolean hasStoredData(OwnedDimensionDescriptor descriptor) { storageCalls.incrementAndGet(); return storedData; }
        @Override public ServerLevel reload(OwnedDimensionDescriptor descriptor) { reloadCalls.incrementAndGet(); return reloads ? level : null; }
        @Override public ServerLevel reload(OwnedDimensionDescriptor descriptor, long requestedSeed) {
            seededReloadCalls.incrementAndGet(); lastRequestedReloadSeed = requestedSeed;
            if (throwSeededReloadLinkage) throw new NoSuchMethodError("controlled explicit seed reload linkage failure");
            return reload(descriptor);
        }
    }

    private static final class ControlledProvider implements ProviderDuplicationPort {
        private final VerseOperationGate gate;
        private final String sourceKey;
        private final String destinationKey;
        private java.util.function.Consumer<ProviderQuiesceCloseTerminal> terminal;
        private final AtomicInteger admissions = new AtomicInteger();
        private final AtomicInteger loads = new AtomicInteger();
        private final AtomicInteger destinationLoads = new AtomicInteger();
        private final Object sourceInstance = new Object();
        private final Object destinationInstance = new Object();
        private boolean admit = true;
        private boolean throwRestore;
        private boolean nullRestore;
        private boolean copiedDirectory = true;
        private boolean foreignDestination;
        private boolean wrongRestoreGeneration;
        private boolean wrongDestinationGeneration;
        private boolean replaceSourceDuringDestinationObservation;
        private boolean sourceCurrent = true;
        private boolean replaceSourceDuringDestinationLoad;
        private boolean throwDestinationLoad;
        private boolean throwDestinationLinkage;
        private boolean nullDestinationLoad;
        private boolean delaySourceObservation;
        private boolean delayDestinationObservation;
        private java.util.function.Consumer<Boolean> pendingSourceObservation;
        private java.util.function.Consumer<Boolean> pendingDestinationObservation;
        private Runnable onDestinationLoad = () -> { };
        private OptionalLong sourceSeed = OptionalLong.empty(), destinationSeed = OptionalLong.empty();
        private boolean seededCapability = true; private long sourceObservedSeed = 9123417733L, destinationObservedSeed = 9123417733L;
        private boolean throwCapabilityLinkage, throwCurrent;

        private ControlledProvider(VerseOperationGate gate, String sourceKey, String destinationKey) {
            this.gate = gate; this.sourceKey = sourceKey; this.destinationKey = destinationKey;
        }
        public boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, java.util.function.Consumer<ProviderQuiesceCloseTerminal> terminal) {
            admissions.incrementAndGet();
            if (admit) this.terminal = terminal;
            return admit;
        }
        public boolean supportsSeededReload() { if (throwCapabilityLinkage) throw new LinkageError("capability linkage"); return seededCapability; }
        public OptionalLong effectiveSeed(Object instance) { return OptionalLong.of(instance == sourceInstance ? sourceObservedSeed : destinationObservedSeed); }
        public RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long generation) {
            require(gate.tryAcquireReadyRead(sourceKey) == null, "source read released before restoration");
            loads.incrementAndGet();
            if (throwRestore) throw new IllegalStateException("controlled restore failure");
            return nullRestore ? null : new RestoredSource(sourceInstance, "restored", wrongRestoreGeneration ? generation + 1 : generation);
        }
        public RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long generation, OptionalLong seed) { sourceSeed = seed; return loadOriginalSource(source, generation); }
        public LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation) {
            require(gate.tryAcquireReadyRead(destinationKey) == null, "destination published before load");
            destinationLoads.incrementAndGet();
            onDestinationLoad.run();
            if (replaceSourceDuringDestinationLoad) sourceCurrent = false;
            if (throwDestinationLoad) throw new IllegalStateException("controlled destination load failure");
            if (throwDestinationLinkage) throw new LinkageError("controlled delayed provider linkage");
            if (nullDestinationLoad) return null;
            return new LoadedDestination(foreignDestination ? new Object() : destinationInstance, "destination",
                    wrongDestinationGeneration ? generation + 1 : generation);
        }
        public LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation, OptionalLong seed) { destinationSeed = seed; return loadCopiedDestination(destination, generation); }
        public boolean isCurrent(OwnedDimensionDescriptor descriptor, Object instance) { if (throwCurrent) throw new LinkageError("current linkage");
            return descriptor.id().equals(sourceKey) ? sourceCurrent && instance == sourceInstance : instance == destinationInstance;
        }
        public boolean isAbsent(OwnedDimensionDescriptor descriptor) { return descriptor.id().equals(destinationKey); }
        public boolean hasCopiedDestination(ClosedDimensionCopyRequest request) { return copiedDirectory; }
        public void awaitCurrent(OwnedDimensionDescriptor descriptor, Object instance, java.util.function.Consumer<Boolean> completion) {
            if (descriptor.id().equals(sourceKey) && delaySourceObservation) { pendingSourceObservation = completion; return; }
            if (descriptor.id().equals(destinationKey) && delayDestinationObservation) { pendingDestinationObservation = completion; return; }
            if (descriptor.id().equals(destinationKey) && replaceSourceDuringDestinationObservation) sourceCurrent = false;
            completion.accept(isCurrent(descriptor, instance));
        }
        private void completeSourceObservation(boolean value) { pendingSourceObservation.accept(value); }
        private void completeDestinationObservation(boolean value) { pendingDestinationObservation.accept(value); }
        private void deliver(ProviderQuiesceCloseTerminal value) { terminal.accept(value); }
    }
}
