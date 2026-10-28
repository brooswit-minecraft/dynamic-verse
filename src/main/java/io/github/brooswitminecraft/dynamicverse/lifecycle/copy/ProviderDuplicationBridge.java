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
        String phase = "terminal evaluation";
        try {
            if (terminal != null) {
                LOGGER.info("Provider duplicate terminal expectedSource=" + source.id() + " terminalSource=" + terminal.sourceKey()
                        + " destination=" + destinationKey + " generation=" + terminal.generation() + " initiallyLoaded="
                        + terminal.initiallyLoaded() + " status=" + terminal.status() + " failurePresent=" + terminal.failurePresent()
                        + " failureStage=" + terminal.failureStage() + " cause=" + terminal.cause());
                if (terminal.cause() != null) {
                    LOGGER.log(Level.WARNING, "Provider duplicate terminal cause expectedSource=" + source.id() + " terminalSource="
                            + terminal.sourceKey() + " destination=" + destinationKey + " generation=" + terminal.generation(), terminal.cause());
                }
                ClosedDimensionDuplicationIntent intent = new ClosedDimensionDuplicationIntent(source.id(), destinationKey, terminal.generation());
                phase = "coordinator evaluation";
                ClosedDimensionDuplicationResult copied = coordinator.duplicate(intent, terminal, operation.copierFence());
                LOGGER.info("Provider duplicate coordinator result source=" + source.id() + " destination=" + destinationKey
                        + " generation=" + terminal.generation() + " outcome=" + copied.outcome() + " detail=" + copied.detail()
                        + " cause=" + copied.cause());
                if (copied.outcome() == ClosedDimensionDuplicationResult.Outcome.COPIED) {
                    phase = "restoration";
                    LOGGER.info("Provider duplicate restoration entered source=" + source.id() + " generation=" + terminal.generation());
                    ProviderDuplicationPort.RestoredSource restored = provider.loadOriginalSource(source, terminal.generation());
                    if (restored != null && restored.instanceIdentity() != null && !restored.instanceIdentity().isBlank()) {
                        LOGGER.info("Provider duplicate restoration returned source=" + source.id() + " generation=" + restored.generation()
                                + " identity=" + restored.instanceIdentity());
                        operation.restored(restored.instanceIdentity(), restored.generation());
                        outcome = BridgeOutcome.RESTORED;
                    } else {
                        LOGGER.warning("Provider duplicate restoration returned null/invalid source=" + source.id()
                                + " generation=" + terminal.generation());
                    }
                }
            } else {
                LOGGER.warning("Provider duplicate terminal absent source=" + source.id() + " destination=" + destinationKey);
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Provider duplicate pipeline failure phase=" + phase + " source=" + source.id()
                    + " destination=" + destinationKey + " generation=" + generation, e);
        } finally {
            operation.close();
            LOGGER.info("Provider duplicate final source=" + source.id() + " destination=" + destinationKey
                    + " generation=" + generation + " outcome=" + outcome);
            try { completion.accept(outcome); } catch (RuntimeException ignored) { }
        }
    }

    public enum BridgeAdmission { ADMITTED, REJECTED }
    public enum BridgeOutcome { RESTORED, QUARANTINED }
}
