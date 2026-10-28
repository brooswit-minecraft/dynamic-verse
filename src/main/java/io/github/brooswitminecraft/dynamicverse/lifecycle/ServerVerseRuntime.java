package io.github.brooswitminecraft.dynamicverse.lifecycle;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionCopyException;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.OwnedDimensionCatalog;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.OwnedDimensionDescriptor;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderDuplicationBridge;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** One server-owned catalog/gate/bridge/access owner. It starts empty and never adopts existing worlds. */
public final class ServerVerseRuntime implements VerseAccessService, VerseLifecycleCommandService, AutoCloseable {
    private final MinecraftServer server;
    private final OwnedDimensionCatalog catalog;
    private final VerseOperationGate gate;
    private final GatedVerseAccessService access;
    private final Queue<Runnable> terminalFeedback = new ArrayDeque<>();
    private final ProviderDuplicationBridge bridge;
    private final String providerUnavailable;
    private boolean closed;

    /** Provider linkage is attempted only at server ownership creation, never from command class initialization. */
    public static ServerVerseRuntime create(MinecraftServer server) {
        try {
            OwnedDimensionCatalog catalog = new OwnedDimensionCatalog();
            VerseOperationGate gate = new VerseOperationGate();
            ProviderDuplicationBridge bridge = ProviderDuplicationBridge.forMinecraftServer(server, catalog, gate);
            return new ServerVerseRuntime(server, bridge, null, catalog, gate);
        } catch (LinkageError | RuntimeException unavailable) {
            return new ServerVerseRuntime(server, null, unavailable.getClass().getSimpleName(), new OwnedDimensionCatalog(), new VerseOperationGate());
        }
    }

    private ServerVerseRuntime(MinecraftServer server, ProviderDuplicationBridge bridge, String providerUnavailable,
                               OwnedDimensionCatalog catalog, VerseOperationGate gate) {
        this.server = Objects.requireNonNull(server); this.bridge = bridge; this.providerUnavailable = providerUnavailable;
        this.catalog = catalog; this.gate = gate; this.access = new GatedVerseAccessService(gate);
    }

    /** Future authorized ownership producer only: it must supply an exact live identity after successful enrollment. */
    public void enrollVerifiedReady(OwnedDimensionDescriptor descriptor, String liveIdentity, long generation) throws ClosedDimensionCopyException {
        if (closed || bridge == null) throw new IllegalStateException("Provider is unavailable: " + providerUnavailable);
        catalog.register(descriptor);
        bridge.registerReady(descriptor.id(), liveIdentity, generation);
    }

    /** Future authorized destination allocation only; staged descriptors are not READY or travelable. */
    public void stageOwnedDestination(OwnedDimensionDescriptor descriptor) { if (closed) throw new IllegalStateException("runtime closed"); catalog.register(descriptor); }

    @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) {
        return access.beginAcquireReadyRead(verseName, sink);
    }

    @Override public DuplicateAdmission beginDuplicate(String source, String destination, Consumer<DuplicateOutcome> completion) {
        if (closed || bridge == null) return DuplicateAdmission.ERR_PROVIDER_UNAVAILABLE;
        if (!catalog.contains(source)) return DuplicateAdmission.ERR_VERSE_NOT_FOUND;
        if (!catalog.contains(destination)) return DuplicateAdmission.ERR_VERSE_NOT_FOUND;
        if (source.equals(destination)) return DuplicateAdmission.ERR_VERSE_ALREADY_EXISTS;
        AtomicBoolean once = new AtomicBoolean();
        try {
            ProviderDuplicationBridge.BridgeAdmission admission = bridge.beginDuplicate(source, destination, outcome -> {
                if (once.compareAndSet(false, true)) terminalFeedback.add(() -> completion.accept(map(outcome)));
            });
            return admission == ProviderDuplicationBridge.BridgeAdmission.ADMITTED ? DuplicateAdmission.ADMITTED : DuplicateAdmission.ERR_VERSE_BUSY;
        } catch (ClosedDimensionCopyException e) { return DuplicateAdmission.ERR_VERSE_NOT_FOUND; }
    }

    @Override public List<ManagedVerseListing> listManaged() {
        return catalog.descriptors().stream().map(descriptor -> listing(descriptor.id())).sorted(Comparator.comparing(ManagedVerseListing::name)).toList();
    }
    private ManagedVerseListing listing(String key) {
        VerseOperationGate.Readiness readiness = gate.readiness(key);
        return switch (readiness.state()) {
            case READY -> new ManagedVerseListing(key, ManagedVerseListing.State.READY, Optional.of(readiness.identity()), Optional.of(readiness.generation()));
            case PENDING -> new ManagedVerseListing(key, ManagedVerseListing.State.PENDING, Optional.empty(), Optional.empty());
            case QUARANTINED -> new ManagedVerseListing(key, ManagedVerseListing.State.QUARANTINED, Optional.empty(), Optional.empty());
        };
    }
    public ServerLevel resolve(ReadyVerseLease lease) {
        if (gate.readiness(lease.verseName()).state() != VerseOperationGate.Readiness.State.READY) return null;
        try { return server.getLevel(catalog.require(lease.verseName()).key()); }
        catch (ClosedDimensionCopyException ignored) { return null; }
    }
    /** Cooperating server tick; callback feedback is dispatched after access revalidation. */
    public void tick() { if (!closed) { access.tick(); while (!terminalFeedback.isEmpty()) terminalFeedback.remove().run(); } }
    @Override public void close() { closed = true; terminalFeedback.clear(); }
    private static DuplicateOutcome map(ProviderDuplicationBridge.BridgeOutcome outcome) {
        return switch (outcome) { case LOADED -> DuplicateOutcome.LOADED; case RESTORED -> DuplicateOutcome.RESTORED; case QUARANTINED -> DuplicateOutcome.QUARANTINED; };
    }
}
