package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Production operation bridge: provider admission -> exact terminal -> confined
 * copier -> normal source load. The shared operation gate stays held throughout.
 */
public final class ProviderDuplicationBridge {
    private final OwnedDimensionCatalog catalog;
    private final VerseOperationGate gate;
    private final ProviderDuplicationPort provider;
    private final ClosedDimensionDuplicationCoordinator coordinator;

    public ProviderDuplicationBridge(OwnedDimensionCatalog catalog, VerseOperationGate gate,
                                     ProviderDuplicationPort provider, DimensionStoragePathResolver paths,
                                     ClosedDimensionCopyPort copier) {
        this.catalog = Objects.requireNonNull(catalog);
        this.gate = Objects.requireNonNull(gate);
        this.provider = Objects.requireNonNull(provider);
        this.coordinator = new ClosedDimensionDuplicationCoordinator(paths, copier, (key, generation) -> {
            throw new IllegalStateException("Bridge supplies the operation-owned copier fence");
        });
    }

    /** Actual supported composition for the pinned provider; no storage/private accessor is involved. */
    public static ProviderDuplicationBridge forMinecraftServer(MinecraftServer server, OwnedDimensionCatalog catalog,
                                                                VerseOperationGate gate) {
        return new ProviderDuplicationBridge(catalog, gate, new MinecraftProviderDuplicationPort(server),
                new MinecraftDimensionStoragePathResolver(server, catalog), new ClosedDimensionCopier());
    }

    public void registerReady(String sourceKey, String levelIdentity, long generation) throws ClosedDimensionCopyException {
        catalog.require(sourceKey);
        gate.publishReady(sourceKey, levelIdentity, generation);
    }

    public BridgeAdmission beginDuplicate(String sourceKey, String destinationKey, Consumer<BridgeOutcome> completion)
            throws ClosedDimensionCopyException {
        OwnedDimensionDescriptor source = catalog.require(sourceKey);
        catalog.require(destinationKey);
        if (sourceKey.equals(destinationKey)) throw new ClosedDimensionCopyException("Source and destination must differ");
        VerseOperationGate.Operation operation;
        try {
            operation = gate.beginOperation(sourceKey, destinationKey);
        } catch (IllegalStateException e) {
            return BridgeAdmission.REJECTED;
        }
        boolean admitted;
        try {
            admitted = provider.beginQuiesceAndClose(source, terminal -> finish(source, destinationKey, operation, terminal, completion));
        } catch (RuntimeException e) {
            operation.close();
            return BridgeAdmission.REJECTED;
        }
        if (!admitted) {
            operation.admissionRefused();
            operation.close();
            return BridgeAdmission.REJECTED;
        }
        return BridgeAdmission.ADMITTED;
    }

    private void finish(OwnedDimensionDescriptor source, String destinationKey, VerseOperationGate.Operation operation,
                        ProviderQuiesceCloseTerminal terminal, Consumer<BridgeOutcome> completion) {
        long generation = terminal == null ? Long.MIN_VALUE : terminal.generation();
        if (!operation.claimTerminal(generation)) return;
        BridgeOutcome outcome = BridgeOutcome.QUARANTINED;
        try {
            if (terminal != null) {
                ClosedDimensionDuplicationIntent intent = new ClosedDimensionDuplicationIntent(source.id(), destinationKey, terminal.generation());
                ClosedDimensionDuplicationResult copied = coordinator.duplicate(intent, terminal, operation.copierFence());
                if (copied.outcome() == ClosedDimensionDuplicationResult.Outcome.COPIED) {
                    ProviderDuplicationPort.RestoredSource restored = provider.loadOriginalSource(source, terminal.generation());
                    if (restored != null && restored.instanceIdentity() != null && !restored.instanceIdentity().isBlank()) {
                        operation.restored(restored.instanceIdentity(), restored.generation());
                        outcome = BridgeOutcome.RESTORED;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        } finally {
            operation.close();
            try { completion.accept(outcome); } catch (RuntimeException ignored) { }
        }
    }

    public enum BridgeAdmission { ADMITTED, REJECTED }
    public enum BridgeOutcome { RESTORED, QUARANTINED }
}
