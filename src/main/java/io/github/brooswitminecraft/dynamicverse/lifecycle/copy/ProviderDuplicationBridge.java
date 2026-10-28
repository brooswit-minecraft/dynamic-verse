package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Production operation bridge: provider admission -> exact terminal -> confined
 * copier -> normal source load. The shared operation gate stays held throughout.
 */
public final class ProviderDuplicationBridge {
    private static final Logger LOGGER = Logger.getLogger(ProviderDuplicationBridge.class.getName());
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
        OwnedDimensionDescriptor destination = catalog.require(destinationKey);
        if (sourceKey.equals(destinationKey)) throw new ClosedDimensionCopyException("Source and destination must differ");
        VerseOperationGate.Operation operation;
        try {
            operation = gate.beginOperation(sourceKey, destinationKey);
        } catch (IllegalStateException e) {
            return BridgeAdmission.REJECTED;
        }
        boolean admitted;
        try {
            admitted = provider.beginQuiesceAndClose(source, terminal -> finish(source, destination, operation, terminal, completion));
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

    private void finish(OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination, VerseOperationGate.Operation operation,
                        ProviderQuiesceCloseTerminal terminal, Consumer<BridgeOutcome> completion) {
        String destinationKey = destination.id();
        long generation = terminal == null ? Long.MIN_VALUE : terminal.generation();
        if (!operation.claimTerminal(generation)) return;
        try {
            new Pipeline(source, destination, operation, terminal, completion).start();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Provider duplicate pipeline failure phase=terminal evaluation source=" + source.id()
                    + " destination=" + destinationKey + " generation=" + generation, e);
            complete(operation, source, destination, generation, completion, BridgeOutcome.QUARANTINED);
        }
    }

    private final class Pipeline {
        private final OwnedDimensionDescriptor source, destination; private final VerseOperationGate.Operation operation;
        private final ProviderQuiesceCloseTerminal terminal; private final Consumer<BridgeOutcome> completion;
        private final long generation; private boolean completed;
        private Pipeline(OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination, VerseOperationGate.Operation operation,
                         ProviderQuiesceCloseTerminal terminal, Consumer<BridgeOutcome> completion) {
            this.source=source; this.destination=destination; this.operation=operation; this.terminal=terminal; this.completion=completion;
            this.generation=terminal == null ? Long.MIN_VALUE : terminal.generation();
        }
        private void start() {
            if (terminal == null) { finish(BridgeOutcome.QUARANTINED); return; }
            ClosedDimensionDuplicationResult copied=coordinator.duplicate(new ClosedDimensionDuplicationIntent(source.id(), destination.id(), generation), terminal, operation.copierFence());
            if (copied.outcome()!=ClosedDimensionDuplicationResult.Outcome.COPIED || copied.copyRequest()==null) { finish(BridgeOutcome.QUARANTINED); return; }
            VerseOperationGate.CopyReservation reservation=operation.reserveCopied(source.id(), destination.id(), generation, copied.copyRequest());
            ProviderDuplicationPort.RestoredSource restored=provider.loadOriginalSource(source,generation);
            if (!valid(restored) || restored.generation()!=generation) { finish(BridgeOutcome.QUARANTINED); return; }
            provider.awaitCurrent(source, restored.instance(), current -> {
                try { afterSource(reservation, restored, current); }
                catch (RuntimeException e) { operation.revokeRestoration(); finish(BridgeOutcome.QUARANTINED); }
            });
        }
        private void afterSource(VerseOperationGate.CopyReservation reservation, ProviderDuplicationPort.RestoredSource restored, boolean current) {
            if (!current) { operation.revokeRestoration(); finish(BridgeOutcome.QUARANTINED); return; }
            operation.restored(restored.instanceIdentity(), restored.generation());
            if (!provider.hasCopiedDestination(reservation.request()) || !provider.isAbsent(destination)) { finish(BridgeOutcome.RESTORED); return; }
            ProviderDuplicationPort.LoadedDestination loaded;
            try { loaded=provider.loadCopiedDestination(destination,generation); }
            catch (RuntimeException e) {
                boolean sourceCurrent=provider.isCurrent(source, restored.instance());
                if (!sourceCurrent) operation.revokeRestoration();
                finish(sourceCurrent ? BridgeOutcome.RESTORED : BridgeOutcome.QUARANTINED); return;
            }
            if (!valid(loaded) || loaded.generation()!=generation) { finish(BridgeOutcome.RESTORED); return; }
            provider.awaitCurrent(destination, loaded.instance(), currentDestination -> {
                try { afterDestination(reservation, restored, loaded, currentDestination); }
                catch (RuntimeException e) {
                    boolean sourceCurrent=provider.isCurrent(source, restored.instance());
                    if (!sourceCurrent) operation.revokeRestoration();
                    finish(sourceCurrent ? BridgeOutcome.RESTORED : BridgeOutcome.QUARANTINED);
                }
            });
        }
        private void afterDestination(VerseOperationGate.CopyReservation reservation, ProviderDuplicationPort.RestoredSource restored,
                                      ProviderDuplicationPort.LoadedDestination loaded, boolean currentDestination) {
            if (!provider.isCurrent(source, restored.instance())) { operation.revokeRestoration(); finish(BridgeOutcome.QUARANTINED); return; }
            if (!currentDestination) { finish(BridgeOutcome.RESTORED); return; }
            operation.publishDestination(reservation, loaded.instanceIdentity(), loaded.generation());
            finish(BridgeOutcome.LOADED);
        }
        private void finish(BridgeOutcome outcome) { if (!completed) { completed=true; complete(operation,source,destination,generation,completion,outcome); } }
    }

    private static void complete(VerseOperationGate.Operation operation, OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination,
                                 long generation, Consumer<BridgeOutcome> completion, BridgeOutcome outcome) {
        operation.close();
        LOGGER.info("Provider duplicate final source=" + source.id() + " destination=" + destination.id()+" generation="+generation+" outcome="+outcome);
        try { completion.accept(outcome); } catch (RuntimeException ignored) { }
    }

    private static boolean valid(ProviderDuplicationPort.RestoredSource source) {
        return source != null && source.instance() != null && source.instanceIdentity() != null && !source.instanceIdentity().isBlank();
    }

    private static boolean valid(ProviderDuplicationPort.LoadedDestination destination) {
        return destination != null && destination.instance() != null && destination.instanceIdentity() != null && !destination.instanceIdentity().isBlank();
    }

    public enum BridgeAdmission { ADMITTED, REJECTED }
    public enum BridgeOutcome { RESTORED, LOADED, QUARANTINED }
}
