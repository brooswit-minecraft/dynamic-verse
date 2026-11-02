package io.github.brooswitminecraft.dynamicverse.lifecycle;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.PlayerRemover;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseResult;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseStatus;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Test-only O1/O2 ordinary-server lifecycle.  It deliberately runs from
 * normal server events, not GameTestServer: O1 writes real ordinary level
 * metadata, and O2 receives the same root in a new JVM.  External wrappers
 * own the pre-launch absent/nonempty and process/FileLock checks; this class
 * repeats the identity checks before Runtime recovery and never resets data.
 */
public final class ProviderOrdinaryRestartFixture {
    public static final String NAME = "ordinary_seeded_restart";
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", NAME);
    public static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ID);
    public static final long SEED = 9_123_417_733L;
    public static final BlockPos SENTINEL = new BlockPos(8, 80, 8);
    /** O1 semantic provenance is immutable; O2 must never rewrite it. */
    private static final String O1_RECEIPT = "dynamicverse-ordinary-restart-o1.properties";
    /** O2 writes its own scoped result only after the retained O1 replay succeeds. */
    private static final String O2_RECEIPT = "dynamicverse-ordinary-restart-o2.properties";
    private static final int OBSERVATION_LIMIT = 160;

    private final String phase;
    private MinecraftServer server;
    private ServerVerseRuntime runtime;
    private Path root;
    private DynamicDimensionRegistry registry;
    private final AtomicReference<VerseLifecycleCommandService.CreateOutcome> createTerminal = new AtomicReference<>();
    private final AtomicInteger createCallbacks = new AtomicInteger();
    private final AtomicReference<QuiesceAndCloseResult> closeTerminal = new AtomicReference<>();
    private final AtomicInteger closeCallbacks = new AtomicInteger();
    private final AtomicReference<VerseReservationSnapshot> preReplayRead = new AtomicReference<>();
    private final AtomicReference<VerseReservationSnapshot> replayRead = new AtomicReference<>();
    private State state = State.NEW;
    private int observations;

    private enum State { NEW, CREATING, CLOSING, REPLAYING, READY_READ, COMPLETE, FAILED }

    public ProviderOrdinaryRestartFixture(String phase) {
        if (!phase.equals("O1") && !phase.equals("O2")) throw new IllegalArgumentException("unsupported ordinary restart phase: " + phase);
        this.phase = phase;
    }

    /** Must run from ServerStartingEvent before {@link ServerVerseRuntime#create(MinecraftServer)} on O2. */
    public static void validateBeforeRuntime(String phase, MinecraftServer server) {
        if (!phase.equals("O2")) return;
        Path root = confinedRoot(server);
        Snapshot expected = readSnapshot(root);
        Path levelDat = root.resolve("level.dat");
        require(Files.isRegularFile(levelDat, LinkOption.NOFOLLOW_LINKS), "O2 refuses missing primary level.dat");
        // The external O2 wrapper compares final post-shutdown level.dat bytes
        // to its immutable O1 receipt before JVM startup.  Here, after the
        // ordinary server has loaded metadata, retain semantic seed/type checks.
        require(server.getWorldData().worldGenOptions().seed() == expected.primarySeed,
                "O2 loaded primary seed differs from O1 retained metadata");
        require(overworldType(server).equals(expected.overworldType), "O2 loaded overworld DimensionType registry identity differs from O1");
        Path dimension = dimensionStorage(root);
        require(Files.isDirectory(dimension, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dimension)
                        && Files.isRegularFile(dimension.resolve("region").resolve("r.0.0.mca"), LinkOption.NOFOLLOW_LINKS),
                "O2 refuses missing or linked retained custom-dimension storage");
        // Provider reload is deliberately registration-free at this point.
        // DynamicDimensions removes an unloaded dynamic key from its live
        // registries, and its public loadDynamicDimension overload calls
        // canCreateDimension before reconstructing that registration.  The
        // retained owned path/state above is the persistence evidence; a live
        // key here would make the supported reload API refuse it.
        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        require(providerReloadAdmission(registry.anyDimensionExists(ID), registry.canCreateDimension(ID), server.getLevel(KEY) != null),
                "O2 provider reload requires an unregistered, creatable retained owned key before replay");
    }

    public void started(MinecraftServer server, ServerVerseRuntime runtime) {
        this.server = server;
        this.runtime = runtime;
        this.root = confinedRoot(server);
        this.registry = DynamicDimensionRegistry.from(server);
        try {
            if (phase.equals("O1")) startO1(); else startO2();
        } catch (RuntimeException failure) { fail("ordinary restart setup failed", failure); }
    }

    public void tick() {
        if (state == State.COMPLETE || state == State.FAILED || server == null) return;
        try {
            if (phase.equals("O1")) tickO1();
            else if (state == State.READY_READ) finishO2Read();
            else tickO2();
        } catch (RuntimeException failure) { fail("ordinary restart observation failed", failure); }
    }

    private void startO1() {
        require(!Files.exists(o1ReceiptPath(), LinkOption.NOFOLLOW_LINKS) && !Files.exists(o2ReceiptPath(), LinkOption.NOFOLLOW_LINKS),
                "O1 refuses existing ordinary restart fixture receipts");
        require(!registry.anyDimensionExists(ID) && registry.canCreateDimension(ID) && server.getLevel(KEY) == null,
                "O1 requires a unique unused owned identifier");
        require(OwnedCreateStorageConfinement.isFreshConfined(root, KEY), "O1 requires an absent confined custom-dimension path");
        VerseLifecycleCommandService.CreateAdmission admission = runtime.beginCreate(NAME,
                VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.of(SEED), outcome -> {
                    createCallbacks.incrementAndGet(); createTerminal.compareAndSet(null, outcome);
                });
        require(admission == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "O1 production create admission=" + admission);
        state = State.CREATING;
    }

    private void tickO1() {
        if (state == State.CREATING) {
            if (createTerminal.get() == null) { await("O1 create never reached READY"); return; }
            require(createTerminal.get() == VerseLifecycleCommandService.CreateOutcome.READY && createCallbacks.get() == 1,
                    "O1 has exactly one READY create outcome");
            ServerLevel created = requireNonNull(server.getLevel(KEY), "O1 current created level");
            require(created.getSeed() == SEED && created.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                    "O1 explicit seed must reach canonical NORMAL generation");
            ManagedVerseStateStore.State durable = ManagedVerseStateFileStore.minecraft(server).read();
            ManagedVerseStateStore.ReadyVerse ready = requireNonNull(durable.ready().get(ID.toString()), "O1 durable READY");
            require(ready.requestedSeed().equals(OptionalLong.of(SEED)) && ready.effectiveSeed() == SEED && ready.generation() > 0
                            && durable.operations().isEmpty(), "O1 persists exact create provenance before close");
            created.setBlock(SENTINEL, Blocks.REDSTONE_LAMP.defaultBlockState(), 0);
            runtime.close();
            require(registry.beginQuiesceAndClose(ID, PlayerRemover.DEFAULT, result -> {
                closeCallbacks.incrementAndGet(); closeTerminal.compareAndSet(null, result);
            }), "O1 forced save/close admission");
            state = State.CLOSING;
            observations = 0;
            return;
        }
        if (state == State.CLOSING) {
            QuiesceAndCloseResult result = closeTerminal.get();
            if (result == null) { await("O1 forced save/close produced no terminal receipt"); return; }
            require(closeCallbacks.get() == 1 && result.key().equals(KEY) && result.status() == QuiesceAndCloseStatus.SAVED_AND_CLOSED
                            && result.failureStage() == null && server.getLevel(KEY) == null,
                    "O1 retains only a successful forced-save-and-close terminal receipt");
            writeO1Snapshot(snapshot(server));
            succeed("O1 created READY, retained primary metadata, and closed the custom level");
        }
    }

    private void startO2() {
        Snapshot expected = readSnapshot(root);
        ManagedVerseStateStore.State durable = ManagedVerseStateFileStore.minecraft(server).read();
        ManagedVerseStateStore.ReadyVerse ready = requireNonNull(durable.ready().get(ID.toString()), "O2 retained READY descriptor");
        require(ready.requestedSeed().equals(OptionalLong.of(SEED)) && ready.effectiveSeed() == SEED && ready.generation() == expected.generation
                        && durable.operations().isEmpty(), "O2 uses retained O1 requested/effective seed and generation");
        require(runtime.listManaged().stream().anyMatch(value -> value.name().equals(ID.toString()) && value.state() == ManagedVerseListing.State.PENDING),
                "O2 replay is excluded from READY until later Runtime observation");
        // Access completions are resolved only by the owned post-tick owner.
        // This request is queued before the first replay observation, so that
        // tick must settle it as unavailable before it can publish READY.
        runtime.beginAcquireReadyRead(NAME, preReplayRead::set);
        state = State.REPLAYING;
    }

    private void tickO2() {
        VerseReservationSnapshot before = preReplayRead.get();
        require(before != null && before.lease().isEmpty(), "O2 refuses read access before replay observation");
        ManagedVerseListing listing = runtime.listManaged().stream().filter(value -> value.name().equals(ID.toString())).findFirst().orElse(null);
        if (listing == null || listing.state() != ManagedVerseListing.State.READY) { await("O2 replay did not publish READY"); return; }
        ServerLevel reloaded = requireNonNull(server.getLevel(KEY), "O2 current replay registration");
        require(reloaded.getSeed() == SEED && reloaded.getBlockState(SENTINEL).equals(Blocks.REDSTONE_LAMP.defaultBlockState())
                        && runtime.recordedEffectiveSeed(ID.toString()).equals(OptionalLong.of(SEED)),
                "O2 replay retains custom world evidence and exact effective seed");
        runtime.beginAcquireReadyRead(NAME, replayRead::set);
        state = State.READY_READ;
        observations = 0;
    }

    private void finishO2Read() {
        VerseReservationSnapshot result = replayRead.get();
        if (result == null) { await("O2 READY read lease was not settled on a later owned tick"); return; }
        ReadyVerseLease lease = requireNonNull(result.lease().orElse(null), "O2 READY read lease");
        ManagedVerseListing listing = runtime.listManaged().stream().filter(value -> value.name().equals(ID.toString())).findFirst().orElse(null);
        ServerLevel reloaded = requireNonNull(server.getLevel(KEY), "O2 current replay registration after read admission");
        try { require(runtime.resolve(lease) == reloaded && listing.generation().orElseThrow() > 0, "O2 resolves the fresh current instance"); }
        finally { lease.close(); }
        writeO2Receipt(snapshot(server));
        succeed("O2 loaded ordinary metadata, replayed retained custom state, and admitted a READY lease");
    }

    private void await(String message) {
        if (++observations >= OBSERVATION_LIMIT) throw new IllegalStateException(message);
    }
    private void succeed(String message) {
        state = State.COMPLETE;
        System.getLogger(ProviderOrdinaryRestartFixture.class.getName()).log(System.Logger.Level.INFO, message);
        server.halt(false);
    }
    private void fail(String message, RuntimeException failure) {
        state = State.FAILED;
        System.getLogger(ProviderOrdinaryRestartFixture.class.getName()).log(System.Logger.Level.ERROR, message, failure);
        if (runtime != null) runtime.close();
        server.halt(false);
    }

    private static Snapshot snapshot(MinecraftServer server) {
        ManagedVerseStateStore.ReadyVerse ready = requireNonNull(ManagedVerseStateFileStore.minecraft(server).read().ready().get(ID.toString()), "O1 durable READY before snapshot");
        return new Snapshot(server.getWorldData().worldGenOptions().seed(), overworldType(server), ready.generation());
    }
    private static String overworldType(MinecraftServer server) {
        return server.registryAccess().registryOrThrow(Registries.DIMENSION_TYPE)
                .getResourceKey(server.overworld().dimensionType()).orElseThrow(() -> new IllegalStateException("ordinary overworld has no registry key"))
                .location().toString();
    }
    private static Path confinedRoot(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "ordinary restart root must be a real directory");
        return root;
    }
    private static Path dimensionStorage(Path root) { return root.resolve("dimensions").resolve("dynamicverse").resolve(NAME); }

    /**
     * Public provider reload accepts retained storage only while its dynamic
     * registry identity is free.  Keep this narrow predicate ordinary-unit
     * testable: it protects the O2 preflight from regressing to the inverse
     * "already registered" expectation without creating/loading a world.
     */
    static boolean providerReloadAdmission(boolean anyDimensionExists, boolean canCreateDimension, boolean currentLevelPresent) {
        return !anyDimensionExists && canCreateDimension && !currentLevelPresent;
    }
    private Path o1ReceiptPath() { return root.resolve("data").resolve(O1_RECEIPT); }
    private Path o2ReceiptPath() { return root.resolve("data").resolve(O2_RECEIPT); }
    private static Snapshot readSnapshot(Path root) {
        Path path = root.resolve("data").resolve(O1_RECEIPT);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "ordinary restart snapshot is missing");
        try {
            Properties properties = new Properties();
            try (StringReader reader = new StringReader(Files.readString(path, StandardCharsets.UTF_8))) { properties.load(reader); }
            require("O1".equals(required(properties, "phase")), "ordinary restart O1 provenance has wrong phase");
            return new Snapshot(Long.parseLong(required(properties, "primarySeed")),
                    required(properties, "overworldType"), Long.parseLong(required(properties, "generation")));
        } catch (IOException | IllegalArgumentException failure) { throw new IllegalStateException("ordinary restart snapshot is malformed", failure); }
    }
    private void writeO1Snapshot(Snapshot value) { writeProperties(o1ReceiptPath(), value, "O1"); }
    private void writeO2Receipt(Snapshot value) { writeProperties(o2ReceiptPath(), value, "O2"); }
    private void writeProperties(Path path, Snapshot value, String phase) {
        try {
            require(!Files.exists(path, LinkOption.NOFOLLOW_LINKS), "ordinary restart receipt already exists: " + path.getFileName());
            Files.createDirectories(path.getParent());
            Properties properties = new Properties();
            properties.setProperty("primarySeed", Long.toString(value.primarySeed));
            properties.setProperty("overworldType", value.overworldType);
            properties.setProperty("generation", Long.toString(value.generation));
            properties.setProperty("phase", phase);
            StringWriter writer = new StringWriter(); properties.store(writer, "ordinary-restart fixture evidence");
            Files.writeString(path, writer.toString(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("cannot write ordinary restart fixture receipt", failure); }
    }
    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + key);
        return value;
    }
    private static <T> T requireNonNull(T value, String message) { if (value == null) throw new IllegalStateException(message); return value; }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private record Snapshot(long primarySeed, String overworldType, long generation) { }
}
