package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Source-only ModDev GameTest harness; do not execute without serialized runtime approval. */
public final class ProviderDuplicationBridgeGameTest {
    @GameTest(templateNamespace = "dynamicverse", template = "empty")
    @PrefixGameTestTemplate(false)
    public void controlledBridgeCallback(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        OwnedDimensionCatalog catalog = new OwnedDimensionCatalog();
        OwnedDimensionDescriptor source = descriptor("source", level);
        catalog.register(source); catalog.register(descriptor("destination", level));
        VerseOperationGate gate = new VerseOperationGate();
        ControlledProvider provider = new ControlledProvider();
        AtomicInteger copies = new AtomicInteger(); AtomicReference<ProviderDuplicationBridge.BridgeOutcome> outcome = new AtomicReference<>();
        ProviderDuplicationBridge bridge = new ProviderDuplicationBridge(catalog, gate, provider,
                intent -> new ClosedDimensionCopyRequest(Path.of("/tmp"), Path.of("source"), Path.of("destination"), intent.sourceKey(), intent.generation()),
                (request, receipt, fence) -> { copies.incrementAndGet(); if (gate.tryAcquireReadyRead(source.id()) != null) throw new AssertionError("premature ready"); });
        bridge.registerReady(source.id(), "old", 1);
        if (bridge.beginDuplicate(source.id(), "dynamicverse:destination", outcome::set) != ProviderDuplicationBridge.BridgeAdmission.ADMITTED) throw new AssertionError("admission");
        provider.terminal.accept(new ProviderQuiesceCloseTerminal(source.id(), 7, true, ClosedDimensionStatus.SAVED_AND_CLOSED, false));
        if (outcome.get() != ProviderDuplicationBridge.BridgeOutcome.RESTORED || copies.get() != 1 || provider.loads != 1) throw new AssertionError("terminal counts/outcome");
        VerseOperationGate.ReadyRead read = gate.tryAcquireReadyRead(source.id());
        if (read == null || !read.identity().equals("restored") || read.generation() != 7) throw new AssertionError("restored readiness");
        read.close();
        helper.succeed();
    }
    private static OwnedDimensionDescriptor descriptor(String path, ServerLevel level) {
        return new OwnedDimensionDescriptor(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", path)),
                level.getChunkSource().getGenerator(), level.dimensionType(), new DynamicDimensionProperties() {
                    public int priority() { return 0; } public Vector3f baseGravity() { return new Vector3f(); }
                    public double basePressure() { return 0; } public float universalDrag() { return 0; } public Vector3f magneticNorth() { return new Vector3f(); }
                });
    }
    private static final class ControlledProvider implements ProviderDuplicationPort {
        Consumer<ProviderQuiesceCloseTerminal> terminal;
        int loads;
        public boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, Consumer<ProviderQuiesceCloseTerminal> terminal) { this.terminal = terminal; return true; }
        public RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long generation) { loads++; return new RestoredSource("restored", generation); }
    }
}
