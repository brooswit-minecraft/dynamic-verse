package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Production operation bridge: provider admission -> exact terminal -> confined
 * copier -> normal source load. The shared operation gate stays held throughout.
 */
public final class ProviderDuplicationBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProviderDuplicationBridge.class);
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
                LOGGER.info("Provider duplicate terminal source={} destination={} generation={} initiallyLoaded={} status={} failurePresent={} failureStage={} cause={}",
                        source.id(), destinationKey, terminal.generation(), terminal.initiallyLoaded(), terminal.status(),
                        terminal.failurePresent(), terminal.failureStage(), terminal.cause());
                ClosedDimensionDuplicationIntent intent = new ClosedDimensionDuplicationIntent(source.id(), destinationKey, terminal.generation());
                LOGGER.info("Provider duplicate copy entered source={} destination={} generation={}", source.id(), destinationKey, terminal.generation());
                ClosedDimensionDuplicationResult copied = coordinator.duplicate(intent, terminal, operation.copierFence());
                if (copied.outcome() == ClosedDimensionDuplicationResult.Outcome.COPIED) {
                    LOGGER.info("Provider duplicate copy completed source={} destination={} generation={}", source.id(), destinationKey, terminal.generation());
                    LOGGER.info("Provider duplicate restoration entered source={} generation={}", source.id(), terminal.generation());
                    ProviderDuplicationPort.RestoredSource restored = provider.loadOriginalSource(source, terminal.generation());
                    if (restored != null && restored.instanceIdentity() != null && !restored.instanceIdentity().isBlank()) {
                        LOGGER.info("Provider duplicate restoration returned source={} generation={} identity={}", source.id(), restored.generation(), restored.instanceIdentity());
                        operation.restored(restored.instanceIdentity(), restored.generation());
                        outcome = BridgeOutcome.RESTORED;
                    } else {
                        LOGGER.warn("Provider duplicate restoration returned null/invalid source={} generation={}", source.id(), terminal.generation());
                    }
                } else {
                    LOGGER.warn("Provider duplicate copy failed source={} destination={} generation={} outcome={} detail={} cause={}",
                            source.id(), destinationKey, terminal.generation(), copied.outcome(), copied.detail(), copied.cause());
                }
            } else {
                LOGGER.warn("Provider duplicate terminal absent source={} destination={}", source.id(), destinationKey);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Provider duplicate restoration failed source={} destination={} generation={}", source.id(), destinationKey, generation, e);
        } finally {
            operation.close();
            LOGGER.info("Provider duplicate final source={} destination={} generation={} outcome={}", source.id(), destinationKey, generation, outcome);
            try { completion.accept(outcome); } catch (RuntimeException ignored) { }
        }
    }

    public enum BridgeAdmission { ADMITTED, REJECTED }
    public enum BridgeOutcome { RESTORED, QUARANTINED }
}
