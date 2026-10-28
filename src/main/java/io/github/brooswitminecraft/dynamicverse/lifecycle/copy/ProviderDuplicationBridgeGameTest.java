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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Source-only ModDev GameTest harness. It composes the actual bridge, gate, and
 * coordinator with controlled ports; do not execute it without serialized runtime approval.
 */
public final class ProviderDuplicationBridgeGameTest {
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
        OwnedDimensionDescriptor source = descriptor("runtime-source", level);
        AtomicReference<ControlledProvider> controlledProvider = new AtomicReference<>();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), descriptor -> descriptor.id().equals(source.id())
                        ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level, descriptor.generator(), descriptor.type(),
                        descriptor.id() + "@" + Integer.toHexString(System.identityHashCode(level)), level.getSeed()) : null,
                (catalog, gate) -> {
                    ControlledProvider provider = new ControlledProvider(gate, source.id(), "dynamicverse:runtime-copy");
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
                });
        String identity = source.id() + "@" + Integer.toHexString(System.identityHashCode(level));
        runtime.enrollVerifiedReady(new ServerVerseRuntime.EnrollmentProvenance(source, level, 1, identity));
        AtomicReference<VerseLifecycleCommandService.DuplicateOutcome> terminal = new AtomicReference<>();
        AtomicInteger terminalCount = new AtomicInteger();
        require(runtime.beginDuplicate(source.id(), "dynamicverse:runtime-copy", outcome -> { terminal.set(outcome); terminalCount.incrementAndGet(); })
                        == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED, "facade admits through actual bridge");
        require(runtime.beginDuplicate(source.id(), "dynamicverse:runtime-copy", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_BUSY, "actual bridge converts busy gate to refusal");
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
        ControlledCreate retained = new ControlledCreate(level); retained.storedData = true; retained.reloads = true;
        ServerVerseRuntime replayed = controlledCreateRuntime(level, retained, false, state);
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
        helper.succeed();
    }

    private static ServerVerseRuntime controlledCreateRuntime(ServerLevel level, ControlledCreate create, boolean wrongSeed) {
        return controlledCreateRuntime(level, create, wrongSeed, new InMemoryManagedVerseStateStore());
    }
    private static ServerVerseRuntime controlledCreateRuntime(ServerLevel level, ControlledCreate create, boolean wrongSeed, ManagedVerseStateStore state) {
        return ServerVerseRuntime.controlled(level.getServer(), descriptor -> create.visible && create.createdKeys.contains(descriptor.id()) ? new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), level,
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
        private final java.util.Set<String> createdKeys = new java.util.HashSet<>();
        private boolean storageExists;
        private boolean visible;
        private boolean visibleAfterCreate = true;
        private boolean returnNull;
        private boolean storedData;
        private boolean reloads;
        private final AtomicInteger reloadCalls = new AtomicInteger();
        private ControlledCreate(ServerLevel level) { this.level = level; }
        @Override public boolean canCreate(ResourceLocation id) { return true; }
        @Override public boolean storageExists(OwnedDimensionDescriptor descriptor) { return storageExists; }
        @Override public ServerLevel create(OwnedDimensionDescriptor descriptor) {
            creates.incrementAndGet();
            createdKeys.add(descriptor.id());
            visible = visibleAfterCreate;
            return returnNull ? null : level;
        }
        @Override public boolean hasStoredData(OwnedDimensionDescriptor descriptor) { return storedData; }
        @Override public ServerLevel reload(OwnedDimensionDescriptor descriptor) { reloadCalls.incrementAndGet(); return reloads ? level : null; }
    }

    private static final class ControlledProvider implements ProviderDuplicationPort {
        private final VerseOperationGate gate;
        private final String sourceKey;
        private final String destinationKey;
        private java.util.function.Consumer<ProviderQuiesceCloseTerminal> terminal;
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
        private boolean nullDestinationLoad;
        private boolean delaySourceObservation;
        private boolean delayDestinationObservation;
        private java.util.function.Consumer<Boolean> pendingSourceObservation;
        private java.util.function.Consumer<Boolean> pendingDestinationObservation;

        private ControlledProvider(VerseOperationGate gate, String sourceKey, String destinationKey) {
            this.gate = gate; this.sourceKey = sourceKey; this.destinationKey = destinationKey;
        }
        public boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, java.util.function.Consumer<ProviderQuiesceCloseTerminal> terminal) {
            if (admit) this.terminal = terminal;
            return admit;
        }
        public RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long generation) {
            require(gate.tryAcquireReadyRead(sourceKey) == null, "source read released before restoration");
            loads.incrementAndGet();
            if (throwRestore) throw new IllegalStateException("controlled restore failure");
            return nullRestore ? null : new RestoredSource(sourceInstance, "restored", wrongRestoreGeneration ? generation + 1 : generation);
        }
        public LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation) {
            require(gate.tryAcquireReadyRead(destinationKey) == null, "destination published before load");
            destinationLoads.incrementAndGet();
            if (replaceSourceDuringDestinationLoad) sourceCurrent = false;
            if (throwDestinationLoad) throw new IllegalStateException("controlled destination load failure");
            if (nullDestinationLoad) return null;
            return new LoadedDestination(foreignDestination ? new Object() : destinationInstance, "destination",
                    wrongDestinationGeneration ? generation + 1 : generation);
        }
        public boolean isCurrent(OwnedDimensionDescriptor descriptor, Object instance) {
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
