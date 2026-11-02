package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.OptionalLong;
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
    /** Public capability probe used by Runtime before it reserves a destination. */
    public boolean supportsSeededReload() {
        try {
            return provider.supportsSeededReload();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    public BridgeAdmission beginDuplicate(String sourceKey, String destinationKey, Consumer<BridgeOutcome> completion)
            throws ClosedDimensionCopyException {
        return beginDuplicate(sourceKey, destinationKey, OptionalLong.empty(), completion);
    }
    public BridgeAdmission beginDuplicate(String sourceKey, String destinationKey, OptionalLong seed, Consumer<BridgeOutcome> completion)
            throws ClosedDimensionCopyException {
        Objects.requireNonNull(seed);
        OwnedDimensionDescriptor source = catalog.require(sourceKey);
        OwnedDimensionDescriptor destination = catalog.require(destinationKey);
        if (seed.isPresent() && !supportsSeededReload()) return BridgeAdmission.REJECTED;
        if (sourceKey.equals(destinationKey)) throw new ClosedDimensionCopyException("Source and destination must differ");
        VerseOperationGate.Operation operation;
        try {
            operation = gate.beginOperation(sourceKey, destinationKey);
        } catch (IllegalStateException e) {
            return BridgeAdmission.REJECTED;
        }
        boolean admitted;
        try {
            admitted = provider.beginQuiesceAndClose(source, terminal -> finish(source, destination, operation, terminal, seed, completion));
        } catch (RuntimeException | LinkageError e) {
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
                        ProviderQuiesceCloseTerminal terminal, OptionalLong seed, Consumer<BridgeOutcome> completion) {
        String destinationKey = destination.id();
        long generation = terminal == null ? Long.MIN_VALUE : terminal.generation();
        if (!operation.claimTerminal(generation)) return;
        try {
            new Pipeline(source, destination, operation, terminal, seed, completion).start();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Provider duplicate pipeline failure phase=terminal evaluation source=" + source.id()
                    + " destination=" + destinationKey + " generation=" + generation, e);
            complete(operation, source, destination, generation, completion, BridgeOutcome.QUARANTINED);
        }
    }

    private final class Pipeline {
        private final OwnedDimensionDescriptor source, destination; private final VerseOperationGate.Operation operation;
        private final ProviderQuiesceCloseTerminal terminal; private final OptionalLong seed; private final Consumer<BridgeOutcome> completion;
        private final long generation; private boolean completed;
        private ProviderDuplicationPort.RestoredSource restored;
        private Pipeline(OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination, VerseOperationGate.Operation operation,
                         ProviderQuiesceCloseTerminal terminal, OptionalLong seed, Consumer<BridgeOutcome> completion) {
            this.source=source; this.destination=destination; this.operation=operation; this.terminal=terminal; this.seed=seed; this.completion=completion;
            this.generation=terminal == null ? Long.MIN_VALUE : terminal.generation();
        }
        private void start() {
            if (terminal == null) { finish(BridgeOutcome.QUARANTINED); return; }
            LOGGER.info("Provider duplicate terminal expectedSource=" + source.id() + " terminalSource=" + terminal.sourceKey()
                    + " destination=" + destination.id() + " generation=" + generation + " initiallyLoaded=" + terminal.initiallyLoaded()
                    + " status=" + terminal.status() + " failurePresent=" + terminal.failurePresent() + " failureStage="
                    + terminal.failureStage() + " cause=" + terminal.cause());
            if (terminal.cause() != null) LOGGER.log(Level.WARNING, "Provider duplicate terminal cause source=" + source.id(), terminal.cause());
            ClosedDimensionDuplicationResult copied=coordinator.duplicate(new ClosedDimensionDuplicationIntent(source.id(), destination.id(), generation), terminal, operation.copierFence());
            if (copied.outcome()!=ClosedDimensionDuplicationResult.Outcome.COPIED || copied.copyRequest()==null) { finish(BridgeOutcome.QUARANTINED); return; }
            VerseOperationGate.CopyReservation reservation=operation.reserveCopied(source.id(), destination.id(), generation, copied.copyRequest());
            LOGGER.info("Provider duplicate restoration entered source=" + source.id() + " generation=" + generation);
            restored=provider.loadOriginalSource(source,generation,seed);
            OptionalLong restoredSeed = provider.effectiveSeed(restored == null ? null : restored.instance());
            if (!valid(restored) || restored.generation()!=generation || (seed.isPresent() && (!restoredSeed.isPresent() || restoredSeed.getAsLong() != seed.getAsLong()))) { finish(BridgeOutcome.QUARANTINED); return; }
            provider.awaitCurrent(source, restored.instance(), current -> {
                try { afterSource(reservation, restored, current); }
                catch (RuntimeException | LinkageError e) { LOGGER.log(Level.WARNING, "Provider duplicate source observation failure", e); operation.revokeRestoration(); finish(BridgeOutcome.QUARANTINED); }
            });
        }
        private void afterSource(VerseOperationGate.CopyReservation reservation, ProviderDuplicationPort.RestoredSource restored, boolean current) {
            if (!current) { operation.revokeRestoration(); finish(BridgeOutcome.QUARANTINED); return; }
            LOGGER.info("Provider duplicate restoration observed current source=" + source.id() + " generation=" + restored.generation());
            operation.restored(restored.instanceIdentity(), restored.generation());
            if (!provider.hasCopiedDestination(reservation.request()) || !provider.isAbsent(destination)) { finish(BridgeOutcome.RESTORED); return; }
            ProviderDuplicationPort.LoadedDestination loaded;
            try { loaded=provider.loadCopiedDestination(destination,generation,seed); }
            catch (RuntimeException | LinkageError e) {
                LOGGER.log(Level.WARNING, "Provider duplicate destination load failure", e);
                boolean sourceCurrent=safeCurrent(source, restored.instance());
                if (!sourceCurrent) operation.revokeRestoration();
                finish(sourceCurrent ? BridgeOutcome.RESTORED : BridgeOutcome.QUARANTINED); return;
            }
            OptionalLong loadedSeed = provider.effectiveSeed(loaded == null ? null : loaded.instance());
            if (!valid(loaded) || loaded.generation()!=generation || (seed.isPresent() && (!loadedSeed.isPresent() || loadedSeed.getAsLong() != seed.getAsLong()))) { finish(BridgeOutcome.RESTORED); return; }
            provider.awaitCurrent(destination, loaded.instance(), currentDestination -> {
                try { afterDestination(reservation, restored, loaded, currentDestination); }
                catch (RuntimeException | LinkageError e) {
                    LOGGER.log(Level.WARNING, "Provider duplicate destination observation failure", e);
                    boolean sourceCurrent=safeCurrent(source, restored.instance());
                    if (!sourceCurrent) operation.revokeRestoration();
                    finish(sourceCurrent ? BridgeOutcome.RESTORED : BridgeOutcome.QUARANTINED);
                }
            });
        }
        private void afterDestination(VerseOperationGate.CopyReservation reservation, ProviderDuplicationPort.RestoredSource restored,
                                      ProviderDuplicationPort.LoadedDestination loaded, boolean currentDestination) {
            if (!safeCurrent(source, restored.instance())) { operation.revokeRestoration(); finish(BridgeOutcome.QUARANTINED); return; }
            if (!currentDestination) { finish(BridgeOutcome.RESTORED); return; }
            operation.publishDestination(reservation, loaded.instanceIdentity(), loaded.generation());
            finish(BridgeOutcome.LOADED);
        }
        private void finish(BridgeOutcome outcome) {
            if (completed) return;
            if (outcome != BridgeOutcome.QUARANTINED && restored != null && !safeCurrent(source, restored.instance())) {
                operation.revokeRestoration(); outcome = BridgeOutcome.QUARANTINED;
            }
            completed=true; complete(operation,source,destination,generation,completion,outcome);
        }
        private boolean safeCurrent(OwnedDimensionDescriptor descriptor, Object instance) {
            try { return provider.isCurrent(descriptor, instance); }
            catch (RuntimeException | LinkageError unavailable) { return false; }
        }
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
