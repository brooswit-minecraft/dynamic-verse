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
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;

/** One server-owned catalog/gate/bridge/access owner. It starts empty and never adopts existing worlds. */
public final class ServerVerseRuntime implements VerseAccessService, VerseLifecycleCommandService, AutoCloseable {
    private final MinecraftServer server;
    private final LevelLookup levels;
    private final OwnedDimensionCatalog catalog;
    private final VerseOperationGate gate;
    private final GatedVerseAccessService access;
    private final Queue<Runnable> terminalFeedback = new ArrayDeque<>();
    private final Set<AdmittedCompletion> admittedCompletions = new LinkedHashSet<>();
    private final BridgeGateway bridge;
    private final String providerUnavailable;
    private boolean closed;

    /** Provider linkage is attempted only at server ownership creation, never from command class initialization. */
    public static ServerVerseRuntime create(MinecraftServer server) {
        try {
            return new ServerVerseRuntime(server, descriptor -> CurrentLevelEvidence.observed(server.getLevel(descriptor.key())),
                    (catalog, gate) -> {
                        ProviderDuplicationBridge bridge = ProviderDuplicationBridge.forMinecraftServer(server, catalog, gate);
                        return new BridgeGateway() {
                            @Override public void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException { bridge.registerReady(key, identity, generation); }
                            @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination, Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException { return bridge.beginDuplicate(source, destination, completion); }
                        };
                    }, null);
        } catch (LinkageError | RuntimeException unavailable) {
            return new ServerVerseRuntime(server, descriptor -> CurrentLevelEvidence.observed(server.getLevel(descriptor.key())), null, unavailable.getClass().getSimpleName());
        }
    }

    public static ServerVerseRuntime controlled(MinecraftServer server, BridgeGatewayFactory factory) {
        return controlled(server, descriptor -> CurrentLevelEvidence.observed(server.getLevel(descriptor.key())), factory);
    }
    public static ServerVerseRuntime controlled(MinecraftServer server, LevelLookup lookup, BridgeGatewayFactory factory) {
        return new ServerVerseRuntime(server, lookup, factory, null);
    }

    private ServerVerseRuntime(MinecraftServer server, LevelLookup levels, BridgeGatewayFactory factory, String providerUnavailable) {
        this.server = Objects.requireNonNull(server); this.levels = Objects.requireNonNull(levels); this.providerUnavailable = providerUnavailable;
        this.catalog = new OwnedDimensionCatalog(); this.gate = new VerseOperationGate();
        this.bridge = factory == null ? null : Objects.requireNonNull(factory.create(catalog, gate));
        this.access = new GatedVerseAccessService(gate);
    }

    /** A successful authorized producer result: retained provenance plus an exact current instance/generation. */
    public record EnrollmentProvenance(OwnedDimensionDescriptor descriptor, ServerLevel successfulInstance, long generation, String identity) {
        public EnrollmentProvenance { Objects.requireNonNull(descriptor); Objects.requireNonNull(successfulInstance); Objects.requireNonNull(identity); }
    }

    /** Authorized producers supply retained descriptor provenance; arbitrary current server levels are never adopted. */
    public void enrollVerifiedReady(EnrollmentProvenance provenance) throws ClosedDimensionCopyException {
        if (closed || bridge == null) throw new IllegalStateException("Provider is unavailable: " + providerUnavailable);
        OwnedDimensionDescriptor descriptor = provenance.descriptor();
        CurrentLevelEvidence current = levels.current(descriptor);
        if (current == null || !current.matchesObservable(descriptor) || current.level() != provenance.successfulInstance()
                || !current.identity().equals(provenance.identity())) {
            throw new IllegalArgumentException("Verified ownership enrollment requires the current descriptor instance");
        }
        catalog.register(descriptor);
        try {
            bridge.registerReady(descriptor.id(), provenance.identity(), provenance.generation());
        }
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
        AdmittedCompletion admitted = new AdmittedCompletion(completion);
        admittedCompletions.add(admitted);
        try {
            ProviderDuplicationBridge.BridgeAdmission admission = bridge.beginDuplicate(source, destination, outcome -> {
                if (admitted.claimTerminal() && !closed) terminalFeedback.add(() -> admitted.complete(map(outcome)));
            });
            if (admission == ProviderDuplicationBridge.BridgeAdmission.ADMITTED) return DuplicateAdmission.ADMITTED;
            admittedCompletions.remove(admitted);
            return DuplicateAdmission.ERR_VERSE_BUSY;
        } catch (ClosedDimensionCopyException e) { admittedCompletions.remove(admitted); return DuplicateAdmission.ERR_VERSE_NOT_FOUND; }
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
            OwnedDimensionDescriptor descriptor = catalog.require(lease.verseName());
            CurrentLevelEvidence evidence = levels.current(descriptor);
            return evidence != null && evidence.matchesObservable(descriptor) && evidence.identity().equals(lease.levelIdentity()) ? evidence.level() : null;
        }
        catch (ClosedDimensionCopyException ignored) { return null; }
    }
    /** Cooperating server tick; callback feedback is dispatched after access revalidation. */
    public void tick() { if (!closed) { access.tick(); while (!terminalFeedback.isEmpty()) terminalFeedback.remove().run(); } }
    public boolean owns(Object candidate) { return server == candidate; }
    @Override public void close() {
        if (!closed) {
            closed = true;
            access.retire();
            terminalFeedback.clear();
            for (AdmittedCompletion completion : List.copyOf(admittedCompletions)) completion.complete(DuplicateOutcome.QUARANTINED);
            admittedCompletions.clear();
        }
    }
    private static String identity(ServerLevel level) { return level.dimension().location() + "@" + Integer.toHexString(System.identityHashCode(level)); }
    private void reserveDuplicateDestination(String source, String destination) throws ClosedDimensionCopyException {
        OwnedDimensionDescriptor retained = catalog.require(source);
        ResourceLocation id = ResourceLocation.tryParse(destination);
        if (id == null || !id.getNamespace().equals("dynamicverse") || id.getPath().isBlank()) throw new IllegalArgumentException("destination must be a Dynamic Verse identifier");
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        DimensionType copiedType = reconstruct(retained.type());
        OwnedDimensionDescriptor destinationDescriptor = new OwnedDimensionDescriptor(key, retained.generator(), copiedType, retained.properties());
        if (levels.current(destinationDescriptor) != null) throw new IllegalArgumentException("destination level already exists");
        catalog.register(destinationDescriptor);
    }
    /** Immutable observation, not a mutable or fabricated ServerLevel. Production derives it from the current server map. */
    public record CurrentLevelEvidence(ResourceKey<Level> key, ServerLevel level, ChunkGenerator generator,
                                       DimensionType type, String identity) {
        public CurrentLevelEvidence {
            Objects.requireNonNull(key); Objects.requireNonNull(level); Objects.requireNonNull(generator);
            Objects.requireNonNull(type); Objects.requireNonNull(identity);
        }
        static CurrentLevelEvidence observed(ServerLevel level) {
            if (level == null) return null;
            return new CurrentLevelEvidence(level.dimension(), level, level.getChunkSource().getGenerator(),
                    level.dimensionType(), ServerVerseRuntime.identity(level));
        }
        boolean matchesObservable(OwnedDimensionDescriptor descriptor) {
            return key.equals(descriptor.key()) && generator == descriptor.generator() && type == descriptor.type()
                    && !identity.isBlank();
        }
    }
    @FunctionalInterface public interface LevelLookup { CurrentLevelEvidence current(OwnedDimensionDescriptor descriptor); }
    @FunctionalInterface public interface BridgeGatewayFactory { BridgeGateway create(OwnedDimensionCatalog catalog, VerseOperationGate gate); }
    public interface BridgeGateway {
        void registerReady(String key, String identity, long generation) throws ClosedDimensionCopyException;
        ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination, Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) throws ClosedDimensionCopyException;
    }
    private static DuplicateOutcome map(ProviderDuplicationBridge.BridgeOutcome outcome) {
        return switch (outcome) { case LOADED -> DuplicateOutcome.LOADED; case RESTORED -> DuplicateOutcome.RESTORED; case QUARANTINED -> DuplicateOutcome.QUARANTINED; };
    }
    private static DimensionType reconstruct(DimensionType type) {
        return new DimensionType(type.fixedTime(), type.hasSkyLight(), type.hasCeiling(), type.ultraWarm(), type.natural(),
                type.coordinateScale(), type.bedWorks(), type.respawnAnchorWorks(), type.minY(), type.height(), type.logicalHeight(),
                type.infiniburn(), type.effectsLocation(), type.ambientLight(), type.monsterSettings());
    }
    private final class AdmittedCompletion {
        private final Consumer<DuplicateOutcome> completion; private final AtomicBoolean terminalClaimed = new AtomicBoolean(); private final AtomicBoolean delivered = new AtomicBoolean();
        private AdmittedCompletion(Consumer<DuplicateOutcome> completion) { this.completion = Objects.requireNonNull(completion); }
        private boolean claimTerminal() { return terminalClaimed.compareAndSet(false, true); }
        private void complete(DuplicateOutcome outcome) { if (delivered.compareAndSet(false, true)) { admittedCompletions.remove(this); completion.accept(outcome); } }
    }
}
