package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
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
            return new LoadedDestination(foreignDestination ? new Object() : destinationInstance, "destination",
                    wrongDestinationGeneration ? generation + 1 : generation);
        }
        public boolean isCurrent(OwnedDimensionDescriptor descriptor, Object instance) {
            return descriptor.id().equals(sourceKey) ? instance == sourceInstance : instance == destinationInstance;
        }
        public boolean isAbsent(OwnedDimensionDescriptor descriptor) { return descriptor.id().equals(destinationKey); }
        public boolean hasCopiedDestination(ClosedDimensionCopyRequest request) { return copiedDirectory; }
        private void deliver(ProviderQuiesceCloseTerminal value) { terminal.accept(value); }
    }
}
