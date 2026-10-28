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
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;

/** One server-owned catalog/gate/bridge/access owner. It starts empty and never adopts existing worlds. */
public final class ServerVerseRuntime implements VerseAccessService, VerseLifecycleCommandService, AutoCloseable {
    private final MinecraftServer server;
    private final LevelLookup levels;
    private final OwnedDimensionCatalog catalog;
    private final VerseOperationGate gate;
    private final GatedVerseAccessService access;
    private final Queue<Runnable> terminalFeedback = new ArrayDeque<>();
    private final Set<Consumer<DuplicateOutcome>> admittedCompletions = new LinkedHashSet<>();
    private final BridgeGateway bridge;
    private final String providerUnavailable;
    private boolean closed;

    /** Provider linkage is attempted only at server ownership creation, never from command class initialization. */
    public static ServerVerseRuntime create(MinecraftServer server) {
        try {
            OwnedDimensionCatalog catalog = new OwnedDimensionCatalog();
            VerseOperationGate gate = new VerseOperationGate();
            ProviderDuplicationBridge bridge = ProviderDuplicationBridge.forMinecraftServer(server, catalog, gate);
            return new ServerVerseRuntime(server, descriptor -> server.getLevel(descriptor.key()), new BridgeGateway() {
                @Override public void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException { bridge.registerReady(key, identity, generation); }
                @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination, Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException { return bridge.beginDuplicate(source, destination, completion); }
            }, null, catalog, gate);
        } catch (LinkageError | RuntimeException unavailable) {
            return new ServerVerseRuntime(server, descriptor -> server.getLevel(descriptor.key()), null, unavailable.getClass().getSimpleName(), new OwnedDimensionCatalog(), new VerseOperationGate());
        }
    }

    public static ServerVerseRuntime controlled(MinecraftServer server, BridgeGateway bridge) {
        return controlled(server, descriptor -> server.getLevel(descriptor.key()), bridge);
    }
    public static ServerVerseRuntime controlled(MinecraftServer server, LevelLookup lookup, BridgeGateway bridge) {
        return new ServerVerseRuntime(server, lookup, bridge, null, new OwnedDimensionCatalog(), new VerseOperationGate());
    }

    private ServerVerseRuntime(MinecraftServer server, LevelLookup levels, BridgeGateway bridge, String providerUnavailable,
                               OwnedDimensionCatalog catalog, VerseOperationGate gate) {
        this.server = Objects.requireNonNull(server); this.levels = Objects.requireNonNull(levels); this.bridge = bridge; this.providerUnavailable = providerUnavailable;
        this.catalog = catalog; this.gate = gate; this.access = new GatedVerseAccessService(gate);
    }

    /** Future authorized ownership producer only: it must supply an exact live identity after successful enrollment. */
    public void enrollVerifiedReady(OwnedDimensionDescriptor descriptor, long generation) throws ClosedDimensionCopyException {
        if (closed || bridge == null) throw new IllegalStateException("Provider is unavailable: " + providerUnavailable);
        ServerLevel level = levels.current(descriptor);
        if (level == null || level.getChunkSource().getGenerator() != descriptor.generator()) {
            throw new IllegalArgumentException("Verified ownership enrollment requires the current descriptor instance");
        }
        String identity = identity(level);
        catalog.register(descriptor);
        try { bridge.registerReady(descriptor.id(), identity, generation); }
        catch (ClosedDimensionCopyException | RuntimeException failed) { catalog.unregister(descriptor.id()); throw failed; }
    }

    /** Future authorized destination allocation only; staged descriptors are not READY or travelable. */
    public void stageOwnedDestination(OwnedDimensionDescriptor descriptor) { if (closed) throw new IllegalStateException("runtime closed"); catalog.register(descriptor); }

    @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) {
        return access.beginAcquireReadyRead(verseName, sink);
    }

    @Override public DuplicateAdmission beginDuplicate(String source, String destination, Consumer<DuplicateOutcome> completion) {
        if (closed || bridge == null) return DuplicateAdmission.ERR_PROVIDER_UNAVAILABLE;
        if (!catalog.contains(source)) return DuplicateAdmission.ERR_VERSE_NOT_FOUND;
        if (!catalog.contains(destination)) {
            try { reserveDuplicateDestination(source, destination); }
            catch (IllegalArgumentException | ClosedDimensionCopyException rejected) { return DuplicateAdmission.ERR_VERSE_NOT_FOUND; }
        }
        if (source.equals(destination)) return DuplicateAdmission.ERR_VERSE_ALREADY_EXISTS;
        AtomicBoolean once = new AtomicBoolean();
        admittedCompletions.add(completion);
        try {
            ProviderDuplicationBridge.BridgeAdmission admission = bridge.beginDuplicate(source, destination, outcome -> {
                if (once.compareAndSet(false, true)) terminalFeedback.add(() -> { admittedCompletions.remove(completion); completion.accept(map(outcome)); });
            });
            if (admission == ProviderDuplicationBridge.BridgeAdmission.ADMITTED) return DuplicateAdmission.ADMITTED;
            admittedCompletions.remove(completion);
            return DuplicateAdmission.ERR_VERSE_BUSY;
        } catch (ClosedDimensionCopyException e) { admittedCompletions.remove(completion); return DuplicateAdmission.ERR_VERSE_NOT_FOUND; }
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
        if (lease == null || lease.isClosed() || !lease.isOwnedBy(access)) return null;
        VerseOperationGate.Readiness current = gate.readiness(lease.verseName());
        if (current.state() != VerseOperationGate.Readiness.State.READY || !lease.levelIdentity().equals(current.identity()) || lease.generation() != current.generation()) return null;
        try {
            ServerLevel level = levels.current(catalog.require(lease.verseName()));
            return level != null && identity(level).equals(lease.levelIdentity()) ? level : null;
        }
        catch (ClosedDimensionCopyException ignored) { return null; }
    }
    /** Cooperating server tick; callback feedback is dispatched after access revalidation. */
    public void tick() { if (!closed) { access.tick(); while (!terminalFeedback.isEmpty()) terminalFeedback.remove().run(); } }
    public boolean owns(MinecraftServer candidate) { return server == candidate; }
    @Override public void close() {
        if (!closed) {
            closed = true;
            access.retire();
            terminalFeedback.clear();
            for (Consumer<DuplicateOutcome> completion : List.copyOf(admittedCompletions)) completion.accept(DuplicateOutcome.QUARANTINED);
            admittedCompletions.clear();
        }
    }
    private static String identity(ServerLevel level) { return level.dimension().location() + "@" + Integer.toHexString(System.identityHashCode(level)); }
    private void reserveDuplicateDestination(String source, String destination) throws ClosedDimensionCopyException {
        OwnedDimensionDescriptor retained = catalog.require(source);
        ResourceLocation id = ResourceLocation.tryParse(destination);
        if (id == null || !id.getNamespace().equals("dynamicverse") || id.getPath().isBlank()) throw new IllegalArgumentException("destination must be a Dynamic Verse identifier");
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        if (levels.current(new OwnedDimensionDescriptor(key, retained.generator(), retained.type(), retained.properties())) != null) throw new IllegalArgumentException("destination level already exists");
        catalog.register(new OwnedDimensionDescriptor(key, retained.generator(), retained.type(), retained.properties()));
    }
    @FunctionalInterface public interface LevelLookup { ServerLevel current(OwnedDimensionDescriptor descriptor); }
    public interface BridgeGateway {
        void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException;
        ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination, Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException;
    }
    private static DuplicateOutcome map(ProviderDuplicationBridge.BridgeOutcome outcome) {
        return switch (outcome) { case LOADED -> DuplicateOutcome.LOADED; case RESTORED -> DuplicateOutcome.RESTORED; case QUARANTINED -> DuplicateOutcome.QUARANTINED; };
    }
}
