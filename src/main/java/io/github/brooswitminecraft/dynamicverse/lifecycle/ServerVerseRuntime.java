package io.github.brooswitminecraft.dynamicverse.lifecycle;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionCopyException;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.OwnedDimensionCatalog;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.OwnedDimensionDescriptor;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderDuplicationBridge;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Map;
import java.util.HashMap;
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
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import org.joml.Vector3f;

/** One server-owned catalog/gate/bridge/access owner. It starts empty and never adopts existing worlds. */
public final class ServerVerseRuntime implements VerseAccessService, VerseLifecycleCommandService, AutoCloseable {
    private final MinecraftServer server;
    private final LevelLookup levels;
    private final OwnedDimensionCatalog catalog;
    private final VerseOperationGate gate;
    private final GatedVerseAccessService access;
    private final Queue<Runnable> terminalFeedback = new ArrayDeque<>();
    private final Set<AdmittedCompletion> admittedCompletions = new LinkedHashSet<>();
    private final Set<AdmittedCreate> admittedCreates = new LinkedHashSet<>();
    private final Map<String, PendingCreate> pendingCreates = new HashMap<>();
    private final Map<String, Long> effectiveSeeds = new HashMap<>();
    private final BridgeGateway bridge;
    private final CreateGateway creates;
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
                    }, CreateGateway.minecraft(server), null);
        } catch (LinkageError | RuntimeException unavailable) {
            return new ServerVerseRuntime(server, descriptor -> CurrentLevelEvidence.observed(server.getLevel(descriptor.key())), null, null, unavailable.getClass().getSimpleName());
        }
    }

    public static ServerVerseRuntime controlled(MinecraftServer server, BridgeGatewayFactory factory) {
        return controlled(server, descriptor -> CurrentLevelEvidence.observed(server.getLevel(descriptor.key())), factory, null);
    }
    public static ServerVerseRuntime controlled(MinecraftServer server, LevelLookup lookup, BridgeGatewayFactory factory) {
        return controlled(server, lookup, factory, null);
    }
    public static ServerVerseRuntime controlled(MinecraftServer server, LevelLookup lookup, BridgeGatewayFactory factory, CreateGateway creates) {
        return new ServerVerseRuntime(server, lookup, factory, creates, null);
    }

    private ServerVerseRuntime(MinecraftServer server, LevelLookup levels, BridgeGatewayFactory factory, CreateGateway creates, String providerUnavailable) {
        this.server = Objects.requireNonNull(server); this.levels = Objects.requireNonNull(levels); this.providerUnavailable = providerUnavailable;
        this.catalog = new OwnedDimensionCatalog(); this.gate = new VerseOperationGate();
        this.bridge = factory == null ? null : Objects.requireNonNull(factory.create(catalog, gate));
        this.creates = creates;
        this.access = new GatedVerseAccessService(gate);
    }

    /** A successful authorized producer result: retained provenance plus an exact current instance/generation. */
    public record EnrollmentProvenance(OwnedDimensionDescriptor descriptor, ServerLevel successfulInstance, long generation, String identity) {
        public EnrollmentProvenance { Objects.requireNonNull(descriptor); Objects.requireNonNull(successfulInstance); Objects.requireNonNull(identity); }
    }

    /** Authorized producers supply retained descriptor provenance; arbitrary current server levels are never adopted. */
    public void enrollVerifiedReady(EnrollmentProvenance provenance) throws ClosedDimensionCopyException {
        enrollVerifiedReady(provenance, null);
    }
    private void enrollVerifiedReady(EnrollmentProvenance provenance, VerseOperationGate.CreateReservation createReservation)
            throws ClosedDimensionCopyException {
        if (closed || bridge == null) throw new IllegalStateException("Provider is unavailable: " + providerUnavailable);
        OwnedDimensionDescriptor descriptor = provenance.descriptor();
        CurrentLevelEvidence current = levels.current(descriptor);
        if (current == null || !current.matchesObservable(descriptor) || current.level() != provenance.successfulInstance()
                || !current.identity().equals(provenance.identity())) {
            throw new IllegalArgumentException("Verified ownership enrollment requires the current descriptor instance");
        }
        boolean alreadyOwned = catalog.contains(descriptor.id());
        if (alreadyOwned && catalog.require(descriptor.id()) != descriptor) {
            throw new IllegalArgumentException("Verified ownership descriptor differs from the staged descriptor");
        }
        if (!alreadyOwned) catalog.register(descriptor);
        try {
            if (createReservation == null) bridge.registerReady(descriptor.id(), provenance.identity(), provenance.generation());
            else createReservation.publishReady(provenance.identity(), provenance.generation());
        }
        catch (ClosedDimensionCopyException | RuntimeException failed) { if (!alreadyOwned) catalog.unregister(descriptor.id()); throw failed; }
    }

    /** Future authorized destination allocation only; staged descriptors are not READY or travelable. */
    public void stageOwnedDestination(OwnedDimensionDescriptor descriptor) { if (closed) throw new IllegalStateException("runtime closed"); catalog.register(descriptor); }

    @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) {
        String key = normalizeOwnedName(verseName);
        if (key == null) {
            VerseReservationSnapshot failed = VerseReservationSnapshot.failed(VerseReservationId.newId(), verseName, VerseAccessError.ERR_VERSE_NOT_FOUND);
            sink.complete(failed);
            return failed;
        }
        return access.beginAcquireReadyRead(key, sink);
    }

    @Override public DuplicateAdmission beginDuplicate(String source, String destination, Consumer<DuplicateOutcome> completion) {
        if (closed || bridge == null) return DuplicateAdmission.ERR_PROVIDER_UNAVAILABLE;
        source = normalizeOwnedName(source);
        destination = normalizeOwnedName(destination);
        if (source == null || destination == null) return DuplicateAdmission.ERR_VERSE_NOT_FOUND;
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

    /**
     * The only create producer.  A supplied seed is rejected before even a
     * gate reservation or provider lookup; omitted seeds are observed from
     * the returned level and must equal the server's effective save seed.
     */
    @Override public CreateAdmission beginCreate(String name, CreateType type, java.util.OptionalLong requestedSeed,
                                                 Consumer<CreateOutcome> completion) {
        Objects.requireNonNull(type); Objects.requireNonNull(requestedSeed); Objects.requireNonNull(completion);
        if (requestedSeed.isPresent()) return CreateAdmission.ERR_PER_VERSE_SEED_UNSUPPORTED;
        if (closed || bridge == null || creates == null) return CreateAdmission.ERR_PROVIDER_UNAVAILABLE;
        ResourceLocation id = ownedCreateId(name);
        if (id == null) return CreateAdmission.ERR_INVALID_NAME_OR_TYPE;
        String key = id.toString();
        if (catalog.contains(key) || pendingCreates.containsKey(key) || !creates.canCreate(id)) return CreateAdmission.ERR_VERSE_ALREADY_EXISTS;
        OwnedDimensionDescriptor descriptor;
        try { descriptor = createDescriptor(id, type); }
        catch (RuntimeException unavailable) { return CreateAdmission.ERR_PROVIDER_UNAVAILABLE; }
        if (creates.storageExists(descriptor) || levels.current(descriptor) != null) return CreateAdmission.ERR_VERSE_ALREADY_EXISTS;
        VerseOperationGate.CreateReservation reservation;
        try { reservation = gate.beginCreate(key); }
        catch (IllegalStateException busy) { return CreateAdmission.ERR_VERSE_BUSY; }
        // Recheck every externally mutable collision after exclusive admission.
        if (!creates.canCreate(id) || creates.storageExists(descriptor) || levels.current(descriptor) != null) {
            reservation.close();
            return CreateAdmission.ERR_VERSE_ALREADY_EXISTS;
        }
        AdmittedCreate admitted = new AdmittedCreate(completion);
        admittedCreates.add(admitted);
        try {
            catalog.register(descriptor); // retains owned exclusion if provider invocation becomes uncertain.
            ServerLevel returned = creates.create(descriptor);
            if (returned == null) {
                reservation.close(); admitted.complete(CreateOutcome.QUARANTINED); return CreateAdmission.ADMITTED;
            }
            pendingCreates.put(key, new PendingCreate(descriptor, returned, reservation, admitted, sharedSaveSeed()));
            return CreateAdmission.ADMITTED;
        } catch (RuntimeException failed) {
            reservation.close();
            admitted.complete(CreateOutcome.QUARANTINED);
            return CreateAdmission.ADMITTED;
        }
    }

    @Override public List<ManagedVerseListing> listManaged() {
        return catalog.descriptors().stream().map(descriptor -> listing(descriptor.id())).sorted(Comparator.comparing(ManagedVerseListing::name)).toList();
    }
    /** Runtime-retained observed save seed for an omitted-seed READY create; persistence is intentionally not claimed. */
    public OptionalLong recordedEffectiveSeed(String verseId) {
        Long seed = effectiveSeeds.get(verseId);
        return seed == null ? OptionalLong.empty() : OptionalLong.of(seed);
    }
    private ManagedVerseListing listing(String key) {
        if (closed) return new ManagedVerseListing(key, ManagedVerseListing.State.QUARANTINED, Optional.empty(), Optional.empty());
        VerseOperationGate.Readiness readiness = gate.readiness(key);
        return switch (readiness.state()) {
            case READY -> new ManagedVerseListing(key, ManagedVerseListing.State.READY, Optional.of(readiness.identity()), Optional.of(readiness.generation()));
            case PENDING -> new ManagedVerseListing(key, ManagedVerseListing.State.PENDING, Optional.empty(), Optional.empty());
            case QUARANTINED -> new ManagedVerseListing(key, ManagedVerseListing.State.QUARANTINED, Optional.empty(), Optional.empty());
        };
    }
    public ServerLevel resolve(ReadyVerseLease lease) {
        if (closed || lease == null || lease.isClosed() || !lease.isOwnedBy(access)) return null;
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
    public void tick() { if (!closed) { access.tick(); observeCreates(); while (!terminalFeedback.isEmpty()) terminalFeedback.remove().run(); } }
    public boolean owns(Object candidate) { return server == candidate; }
    @Override public void close() {
        if (!closed) {
            closed = true;
            access.retire();
            terminalFeedback.clear();
            for (PendingCreate pending : List.copyOf(pendingCreates.values())) pending.fail();
            pendingCreates.clear();
            for (AdmittedCreate completion : List.copyOf(admittedCreates)) completion.forceQuarantine();
            admittedCreates.clear();
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
    private void observeCreates() {
        for (PendingCreate pending : List.copyOf(pendingCreates.values())) pending.observe();
    }
    private long sharedSaveSeed() { return server.getWorldData().worldGenOptions().seed(); }
    private static ResourceLocation ownedCreateId(String name) {
        if (name == null || !name.matches("[a-z0-9_-]{1,32}")) return null;
        return ResourceLocation.fromNamespaceAndPath("dynamicverse", name);
    }
    /** One command/runtime boundary: bare approved names are ours; explicit forms must already be dynamicverse-owned. */
    private static String normalizeOwnedName(String name) {
        if (name == null) return null;
        if (name.matches("[a-z0-9_-]{1,32}")) return ResourceLocation.fromNamespaceAndPath("dynamicverse", name).toString();
        ResourceLocation id = ResourceLocation.tryParse(name);
        return id != null && id.getNamespace().equals("dynamicverse") && id.getPath().matches("[a-z0-9_-]{1,32}") ? id.toString() : null;
    }
    private OwnedDimensionDescriptor createDescriptor(ResourceLocation id, CreateType type) {
        ChunkGenerator generator = switch (type) {
            case NORMAL -> new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(server.registryAccess()
                    .registryOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                    .getHolderOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)), server.registryAccess()
                    .registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD));
            case SUPERFLAT -> new FlatLevelSource(FlatLevelGeneratorSettings.getDefault(server.registryAccess().lookupOrThrow(Registries.BIOME),
                    server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET), server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE)));
        };
        DimensionType overworld = server.overworld().dimensionType();
        DimensionType fresh = reconstruct(overworld);
        return new OwnedDimensionDescriptor(ResourceKey.create(Registries.DIMENSION, id), generator, fresh, defaultProperties());
    }
    private static DynamicDimensionProperties defaultProperties() {
        return new DynamicDimensionProperties() {
            @Override public int priority() { return 0; }
            @Override public Vector3f baseGravity() { return new Vector3f(); }
            @Override public double basePressure() { return 0; }
            @Override public float universalDrag() { return 0; }
            @Override public Vector3f magneticNorth() { return new Vector3f(); }
        };
    }
    /** Immutable observation, not a mutable or fabricated ServerLevel. Production derives it from the current server map. */
    public record CurrentLevelEvidence(ResourceKey<Level> key, ServerLevel level, ChunkGenerator generator,
                                       DimensionType type, String identity, long effectiveSeed) {
        public CurrentLevelEvidence {
            Objects.requireNonNull(key); Objects.requireNonNull(level); Objects.requireNonNull(generator);
            Objects.requireNonNull(type); Objects.requireNonNull(identity);
        }
        static CurrentLevelEvidence observed(ServerLevel level) {
            if (level == null) return null;
            return new CurrentLevelEvidence(level.dimension(), level, level.getChunkSource().getGenerator(),
                    level.dimensionType(), ServerVerseRuntime.identity(level), level.getSeed());
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
    private final class AdmittedCreate {
        private final Consumer<CreateOutcome> completion;
        private CreateOutcome queued;
        private final AtomicBoolean delivered = new AtomicBoolean();
        private AdmittedCreate(Consumer<CreateOutcome> completion) { this.completion = completion; }
        private synchronized void complete(CreateOutcome outcome) {
            if (delivered.get() || queued != null) return;
            queued = outcome;
            terminalFeedback.add(this::deliverQueued);
        }
        private synchronized void forceQuarantine() {
            if (delivered.get()) return;
            queued = CreateOutcome.QUARANTINED;
            deliverQueued();
        }
        private synchronized void deliverQueued() {
            if (queued != null && delivered.compareAndSet(false, true)) {
                CreateOutcome outcome = queued;
                admittedCreates.remove(this);
                completion.accept(outcome);
            }
        }
    }
    private final class PendingCreate {
        private static final int OBSERVATION_LIMIT = 20;
        private final OwnedDimensionDescriptor descriptor;
        private final ServerLevel returned;
        private final VerseOperationGate.CreateReservation reservation;
        private final AdmittedCreate completion;
        private final long expectedSeed;
        private int observations;
        private PendingCreate(OwnedDimensionDescriptor descriptor, ServerLevel returned, VerseOperationGate.CreateReservation reservation,
                              AdmittedCreate completion, long expectedSeed) {
            this.descriptor = descriptor; this.returned = returned; this.reservation = reservation;
            this.completion = completion; this.expectedSeed = expectedSeed;
        }
        private void observe() {
            if (closed) { fail(); return; }
            CurrentLevelEvidence current = levels.current(descriptor);
            if (current != null && current.matchesObservable(descriptor) && current.level() == returned
                    && current.effectiveSeed() == expectedSeed) {
                try {
                    enrollVerifiedReady(new EnrollmentProvenance(descriptor, returned, nextCreateGeneration(), current.identity()), reservation);
                    effectiveSeeds.put(descriptor.id(), current.effectiveSeed());
                    pendingCreates.remove(descriptor.id());
                    completion.complete(CreateOutcome.READY);
                    return;
                } catch (ClosedDimensionCopyException | RuntimeException ignored) { /* retain quarantine below */ }
            }
            if (++observations >= OBSERVATION_LIMIT) fail();
        }
        private void fail() {
            pendingCreates.remove(descriptor.id());
            reservation.close();
            completion.complete(CreateOutcome.QUARANTINED);
        }
    }
    private long createGeneration;
    private long nextCreateGeneration() { return ++createGeneration; }

    /** Provider and filesystem boundary used by the one production create path and its controlled tests. */
    public interface CreateGateway {
        boolean canCreate(ResourceLocation id);
        boolean storageExists(OwnedDimensionDescriptor descriptor);
        ServerLevel create(OwnedDimensionDescriptor descriptor);
        static CreateGateway minecraft(MinecraftServer server) {
            DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
            return new CreateGateway() {
                @Override public boolean canCreate(ResourceLocation id) { return registry.canCreateDimension(id) && !registry.anyDimensionExists(id); }
                @Override public boolean storageExists(OwnedDimensionDescriptor descriptor) {
                    java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
                    return !OwnedCreateStorageConfinement.isFreshConfined(root, descriptor.key());
                }
                @Override public ServerLevel create(OwnedDimensionDescriptor descriptor) {
                    return registry.createDynamicDimension(descriptor.key().location(), descriptor.generator(), descriptor.type(), descriptor.properties());
                }
            };
        }
    }
}
