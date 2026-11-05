package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;
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
import net.minecraft.world.level.storage.LevelResource;

/**
 * Two-JVM safe-delete crash-recovery fixture (prepared for review; not yet
 * executed — see docs/real-provider-safe-delete-acceptance-plan-20261104.md).
 *
 * <p>Identity is anchored to a single wrapper-owned nonce file
 * ({@code dynamicverse-crash-delete-expected-nonce.txt}), written by the
 * external wrapper before Phase A ever launches. Both phases, and the
 * wrapper itself, read it back and refuse on any mismatch; this is what
 * ties a marker/result to this exact wrapper/root rather than trusting a
 * self-generated value. {@link #validateBeforeRuntime} runs from
 * {@code ServerStartingEvent}, before {@link ServerVerseRuntime#create},
 * mirroring {@link ProviderOrdinaryRestartFixture#validateBeforeRuntime} --
 * Phase B's marker/durable-snapshot cross-check happens there, strictly
 * before production recovery runs, not after.</p>
 *
 * <p>Phase A creates a real target and a real unrelated verse through
 * production {@link ServerVerseRuntime#create(MinecraftServer)} wiring,
 * writes a distinct sentinel block into each and forces a real save,
 * admits a real confirmed delete, re-reads the real durable state file to
 * recover the admitted operation's own id/generation, publishes an atomic
 * marker (temp file + {@code ATOMIC_MOVE}, so no reader can observe a
 * partially-written file) recording that exact snapshot plus this JVM's
 * own PID and the echoed expected nonce, then blocks the server thread
 * forever. {@code beginQuiesceAndClose} only enqueues a ticket; the real
 * close only drains on a later {@code tickServer}-injected hook. Never
 * returning from this method, before the server ever reaches its tick
 * loop, is therefore a deterministic barrier -- the admitted delete
 * provably never reaches a terminal, because the server thread this runs
 * on is the only thread that could ever drive that drain. This class never
 * kills itself; an external wrapper owns that, against the exact PID this
 * marker reports, re-verified immediately before every signal.</p>
 *
 * <p>Phase B is a second, independently launched JVM against the same
 * confined root. {@link #validateBeforeRuntime} cross-checks the marker
 * against the still-PENDING durable operation before production recovery
 * runs; {@link #started} then observes the recovered result (an explicit,
 * completion-confirmed rejection with no lease for the target, the target
 * never reloaded, the unrelated verse replayed through a real lease and
 * resolved current instance with its sentinel intact, and the target's
 * on-disk storage directory still present), writes a result receipt linked
 * to the same operation id/generation, and requests a normal graceful stop
 * -- Phase B is the recovery check, not another crash.</p>
 */
public final class ProviderSafeDeleteCrashFixture {
    /** Supplied by the launch contract itself (build.gradle's run registration), not re-derived. */
    private static final String EXPECTED_GAME_ROOT_PROPERTY = "dynamicverse.providerSafeDeleteCrashExpectedGameRoot";
    public static final String TARGET_NAME = "crash_delete_target";
    public static final ResourceLocation TARGET_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", TARGET_NAME);
    public static final ResourceKey<Level> TARGET_KEY = ResourceKey.create(Registries.DIMENSION, TARGET_ID);
    public static final String UNRELATED_NAME = "crash_unrelated";
    public static final ResourceLocation UNRELATED_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", UNRELATED_NAME);
    public static final ResourceKey<Level> UNRELATED_KEY = ResourceKey.create(Registries.DIMENSION, UNRELATED_ID);
    public static final BlockPos TARGET_SENTINEL = new BlockPos(8, 80, 8);
    public static final BlockPos UNRELATED_SENTINEL = new BlockPos(8, 80, 8);

    private static final String MARKER_FILE = "dynamicverse-crash-delete-phase-a-marker.properties";
    private static final String RESULT_FILE = "dynamicverse-crash-delete-phase-b-result.properties";
    /** Wrapper-owned; written once, before Phase A launches. Neither phase ever writes it. */
    private static final String NONCE_FILE = "dynamicverse-crash-delete-expected-nonce.txt";

    private final String phase;

    public ProviderSafeDeleteCrashFixture(String phase) {
        if (!phase.equals("A") && !phase.equals("B")) throw new IllegalArgumentException("unsupported safe-delete crash phase: " + phase);
        this.phase = phase;
    }

    /** Must run from ServerStartingEvent before {@link ServerVerseRuntime#create(MinecraftServer)}. */
    public static void validateBeforeRuntime(String phase, MinecraftServer server) {
        Path game = gameRoot(server);
        String expectedNonce = readNonce(game);
        if (phase.equals("A")) {
            require(!Files.exists(game.resolve(MARKER_FILE), LinkOption.NOFOLLOW_LINKS), "phase A refuses a retained prior marker");
            require(!Files.exists(game.resolve(RESULT_FILE), LinkOption.NOFOLLOW_LINKS), "phase A refuses a retained prior phase B result");
            return;
        }
        Path markerPath = game.resolve(MARKER_FILE);
        require(Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(markerPath), "phase B requires phase A's real marker file");
        require(!Files.exists(game.resolve(RESULT_FILE), LinkOption.NOFOLLOW_LINKS), "phase B refuses a retained prior result");
        Properties marker = readProperties(markerPath);
        require(expectedNonce.equals(require(marker.getProperty("nonce"), "marker missing nonce")), "marker nonce does not match the wrapper-owned expected nonce");
        require(game.toString().equals(require(marker.getProperty("gameRoot"), "marker missing gameRoot")), "marker gameRoot does not match this confined game root");
        String targetKey = require(marker.getProperty("targetKey"), "marker missing targetKey");
        require(TARGET_ID.toString().equals(targetKey), "marker targetKey does not match the expected crash target");
        UUID operationId = UUID.fromString(require(marker.getProperty("operationId"), "marker missing operationId"));
        long targetGeneration = Long.parseLong(require(marker.getProperty("targetGeneration"), "marker missing targetGeneration"));

        ManagedVerseStateStore.State durable = ManagedVerseStateFileStore.minecraft(server).read();
        ManagedVerseStateStore.OperationIntent intent = durable.operations().get(operationId);
        require(intent != null, "the exact recorded operation is no longer present in the durable file before recovery");
        require(intent.kind() == ManagedVerseStateStore.OperationKind.DELETE
                        && targetKey.equals(intent.destination())
                        && intent.phase() == ManagedVerseStateStore.OperationPhase.PENDING
                        && intent.destinationDescriptor().generation() == targetGeneration,
                "the durable operation no longer matches phase A's recorded snapshot before recovery");
    }

    public void started(MinecraftServer server, ServerVerseRuntime runtime) {
        if (phase.equals("A")) phaseA(server, runtime); else phaseB(server, runtime);
    }

    private void phaseA(MinecraftServer server, ServerVerseRuntime runtime) {
        Path game = gameRoot(server);
        Path world = worldRoot(server);
        String expectedNonce = readNonce(game);

        require(runtime.beginCreate(TARGET_NAME, VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "phase A target create admitted");
        require(runtime.beginCreate(UNRELATED_NAME, VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "phase A unrelated create admitted");
        runtime.tick();
        require(isReady(runtime, TARGET_ID), "target READY before delete");
        require(isReady(runtime, UNRELATED_ID), "unrelated READY before delete");

        writeSentinelAndSave(server, TARGET_KEY, TARGET_SENTINEL, Blocks.REDSTONE_LAMP.defaultBlockState());
        writeSentinelAndSave(server, UNRELATED_KEY, UNRELATED_SENTINEL, Blocks.GLOWSTONE.defaultBlockState());
        // Meaningful saved-content evidence, hashed now while the target is
        // still loaded and known-good; Phase B re-hashes the identical raw
        // file without ever loading/reloading the quarantined target.
        String targetRegionHash = hashFile(targetRegionFile(world));

        require(runtime.beginConfirmedDelete(TARGET_ID.toString(), ignored -> {
                    throw new IllegalStateException("phase A must never observe a delete terminal: the barrier below is supposed to prevent one");
                }) == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                "confirmed delete admitted");

        ManagedVerseStateStore.State durable = ManagedVerseStateFileStore.minecraft(server).read();
        ManagedVerseStateStore.OperationIntent intent = durable.operations().values().stream()
                .filter(op -> op.kind() == ManagedVerseStateStore.OperationKind.DELETE
                        && TARGET_ID.toString().equals(op.destination())
                        && op.phase() == ManagedVerseStateStore.OperationPhase.PENDING)
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "durable PENDING delete intent is not readable from the real state file before any marker is published"));

        writeMarker(game, expectedNonce, intent, targetRegionHash);

        // Deterministic barrier, not a sleep-as-proof: admission above only
        // enqueued a QuiesceAndCloseTicket; the real close only drains from
        // MinecraftServerMixin's tickServer injection. This call never
        // returns, so that tick -- and therefore the close -- provably never
        // happens on this JVM. Only an external forced kill of the exact PID
        // just published ends this block.
        for (;;) {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ignored) { }
        }
    }

    private void phaseB(MinecraftServer server, ServerVerseRuntime runtime) {
        Path game = gameRoot(server);
        Path world = worldRoot(server);
        Properties marker = readProperties(game.resolve(MARKER_FILE));
        UUID operationId = UUID.fromString(marker.getProperty("operationId"));
        long targetGeneration = Long.parseLong(marker.getProperty("targetGeneration"));
        String targetRegionHashBefore = require(marker.getProperty("targetRegionHash"), "marker missing targetRegionHash");

        AtomicInteger targetCompletions = new AtomicInteger();
        AtomicReference<VerseReservationSnapshot> targetRead = new AtomicReference<>();
        runtime.beginAcquireReadyRead(TARGET_ID.toString(), result -> { targetCompletions.incrementAndGet(); targetRead.set(result); });
        runtime.tick();
        boolean targetQuarantined = runtime.listManaged().stream()
                .anyMatch(item -> item.name().equals(TARGET_ID.toString()) && item.state() == ManagedVerseListing.State.QUARANTINED);
        boolean targetRejectionCompleted = targetCompletions.get() == 1 && targetRead.get() != null
                && targetRead.get().phase() == VerseReservationPhase.FAILED && targetRead.get().lease().isEmpty();
        boolean targetNeverReloaded = server.getLevel(TARGET_KEY) == null;

        // The exact admitted operation must still be present post-recovery,
        // now transitioned to the restart-interrupted terminal -- not merely
        // the same id echoed back without checking what happened to it.
        ManagedVerseStateStore.OperationIntent postRecovery = ManagedVerseStateFileStore.minecraft(server).read().operations().get(operationId);
        boolean targetOperationInterruptedPostRecovery = postRecovery != null
                && postRecovery.phase() == ManagedVerseStateStore.OperationPhase.FAILED
                && "ERR_OPERATION_INTERRUPTED".equals(postRecovery.error())
                && TARGET_ID.toString().equals(postRecovery.destination());

        AtomicReference<VerseReservationSnapshot> unrelatedRead = new AtomicReference<>();
        runtime.beginAcquireReadyRead(UNRELATED_ID.toString(), unrelatedRead::set);
        runtime.tick();
        boolean unrelatedLeaseAcquired = unrelatedRead.get() != null && unrelatedRead.get().lease().isPresent();
        boolean unrelatedSentinelIntact = false;
        if (unrelatedLeaseAcquired) {
            ReadyVerseLease lease = unrelatedRead.get().lease().get();
            try {
                ServerLevel resolved = runtime.resolve(lease);
                unrelatedSentinelIntact = resolved != null
                        && resolved.getBlockState(UNRELATED_SENTINEL).equals(Blocks.GLOWSTONE.defaultBlockState());
            } finally {
                lease.close();
            }
        }

        Path targetDir = world.resolve("dimensions").resolve("dynamicverse").resolve(TARGET_NAME);
        boolean targetStorageIntact = Files.isDirectory(targetDir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(targetDir);
        // Never loads the quarantined target to inspect it -- only re-hashes
        // the exact same raw region file Phase A hashed while it was loaded.
        boolean targetContentIntact = targetStorageIntact
                && targetRegionHashBefore.equals(hashFile(targetRegionFile(world)));

        require(targetQuarantined, "the interrupted delete's target must be quarantined after recovery, never silently dropped from the catalog");
        require(targetRejectionCompleted, "a read acquisition against the quarantined target must actually complete, as an explicit FAILED result with no lease -- not merely never callback");
        require(targetNeverReloaded, "an interrupted delete's target must never be reloaded as a real ServerLevel during recovery");
        require(targetOperationInterruptedPostRecovery, "the exact admitted operation must persist post-recovery as the restart-interrupted terminal, not merely share an id");
        require(unrelatedLeaseAcquired, "the unrelated verse must still hand out a real lease after recovery");
        require(unrelatedSentinelIntact, "the unrelated verse's resolved current instance must still carry its own sentinel block");
        require(targetStorageIntact, "an interrupted delete must never have removed the target's on-disk storage");
        require(targetContentIntact, "an interrupted delete must never have altered the target's saved region content");

        writeResult(game, marker.getProperty("nonce"), operationId, targetGeneration, targetContentIntact,
                targetQuarantined, targetRejectionCompleted, targetNeverReloaded, targetOperationInterruptedPostRecovery,
                unrelatedLeaseAcquired, unrelatedSentinelIntact, targetStorageIntact);
        server.halt(false);
    }

    private static Path targetRegionFile(Path world) {
        // BlockPos(8,80,8) maps to chunk (0,0) -> region (0,0); the same
        // convention ProviderOrdinaryRestartFixture already relies on.
        return world.resolve("dimensions").resolve("dynamicverse").resolve(TARGET_NAME).resolve("region").resolve("r.0.0.mca");
    }

    private static String hashFile(Path file) {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file), "expected a real region file: " + file);
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (IOException | java.security.NoSuchAlgorithmException bad) {
            throw new IllegalStateException("cannot hash " + file, bad);
        }
    }

    private static void writeSentinelAndSave(MinecraftServer server, ResourceKey<Level> key, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        ServerLevel level = server.getLevel(key);
        require(level != null, "sentinel write requires the level to actually be loaded: " + key);
        level.setBlock(pos, state, 0);
        level.save(null, true, false);
        require(level.getBlockState(pos).equals(state), "sentinel write did not take effect: " + key);
    }

    private static boolean isReady(ServerVerseRuntime runtime, ResourceLocation id) {
        return runtime.listManaged().stream().anyMatch(item -> item.name().equals(id.toString()) && item.state() == ManagedVerseListing.State.READY);
    }

    private void writeMarker(Path game, String expectedNonce, ManagedVerseStateStore.OperationIntent intent, String targetRegionHash) {
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("processStartInstant", ProcessHandle.current().info().startInstant()
                .map(Object::toString).orElse("unknown"));
        properties.setProperty("nonce", expectedNonce);
        properties.setProperty("gameRoot", game.toString());
        properties.setProperty("operationId", intent.id().toString());
        properties.setProperty("targetKey", intent.destination());
        properties.setProperty("targetGeneration", Long.toString(intent.destinationDescriptor().generation()));
        properties.setProperty("targetRegionHash", targetRegionHash);
        properties.setProperty("durablePendingVerified", "true");
        writePropertiesAtomic(game.resolve(MARKER_FILE), properties);
    }

    private void writeResult(Path game, String nonce, UUID operationId, long targetGeneration, boolean targetContentIntact,
                              boolean targetQuarantined, boolean targetRejectionCompleted, boolean targetNeverReloaded,
                              boolean targetOperationInterruptedPostRecovery,
                              boolean unrelatedLeaseAcquired, boolean unrelatedSentinelIntact, boolean targetStorageIntact) {
        Properties properties = new Properties();
        properties.setProperty("nonce", nonce);
        properties.setProperty("operationId", operationId.toString());
        properties.setProperty("targetGeneration", Long.toString(targetGeneration));
        properties.setProperty("targetContentIntact", Boolean.toString(targetContentIntact));
        properties.setProperty("targetQuarantined", Boolean.toString(targetQuarantined));
        properties.setProperty("targetRejectionCompleted", Boolean.toString(targetRejectionCompleted));
        properties.setProperty("targetNeverReloaded", Boolean.toString(targetNeverReloaded));
        properties.setProperty("targetOperationInterruptedPostRecovery", Boolean.toString(targetOperationInterruptedPostRecovery));
        properties.setProperty("unrelatedLeaseAcquired", Boolean.toString(unrelatedLeaseAcquired));
        properties.setProperty("unrelatedSentinelIntact", Boolean.toString(unrelatedSentinelIntact));
        properties.setProperty("targetStorageIntact", Boolean.toString(targetStorageIntact));
        writePropertiesAtomic(game.resolve(RESULT_FILE), properties);
    }

    /** CREATE_NEW staging sibling, forced, then one atomic rename -- no reader can ever observe a partial write. */
    private static void writePropertiesAtomic(Path file, Properties properties) {
        Path staging = file.resolveSibling(file.getFileName() + ".staging-" + UUID.randomUUID() + ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                OutputStream out = java.nio.channels.Channels.newOutputStream(channel);
                properties.store(out, "Dynamic Verse safe-delete crash fixture receipt");
                out.flush();
                channel.force(true);
            }
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException refuseOverwrite) {
            throw new IllegalStateException("refuses to overwrite an existing receipt: " + file, refuseOverwrite);
        } catch (IOException bad) {
            throw new IllegalStateException("cannot write receipt " + file, bad);
        }
    }

    private static Properties readProperties(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { properties.load(in); }
        catch (IOException bad) { throw new IllegalStateException("cannot read receipt " + file, bad); }
        return properties;
    }

    private static String readNonce(Path root) {
        Path file = root.resolve(NONCE_FILE);
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file),
                "wrapper-owned expected-nonce file is missing or invalid: " + file);
        try {
            String value = Files.readString(file).strip();
            require(!value.isEmpty(), "wrapper-owned expected-nonce file is empty");
            return value;
        } catch (IOException bad) { throw new IllegalStateException("cannot read expected nonce", bad); }
    }

    /**
     * {@code LevelResource.ROOT} is the WORLD save directory
     * ({@code gameDir/<level-name>}), not the game directory the JVM was
     * launched with (the wrapper's {@code --gameDir}) -- a prior draft of
     * this fixture conflated the two and would have refused to start. This
     * derives and verifies the game root from the world root's own
     * canonical parent via {@link #gameRootFromWorldRoot}, which is
     * deliberately free of any {@code MinecraftServer} dependency so it is
     * directly unit-testable. A basename/shape match alone is not
     * confinement: a same-named, same-shaped pair retained or copied
     * somewhere else on disk must still be refused, so the resolved game
     * root is additionally required to equal the exact absolute path the
     * launch contract itself supplied via {@link #EXPECTED_GAME_ROOT_PROPERTY}.
     */
    private static Path worldRoot(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        gameRootFromWorldRoot(world, expectedGameRoot()); // validates the full relationship; result discarded here on purpose.
        return world;
    }

    private static Path gameRoot(MinecraftServer server) {
        return gameRootFromWorldRoot(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), expectedGameRoot());
    }

    private static Path expectedGameRoot() {
        String raw = System.getProperty(EXPECTED_GAME_ROOT_PROPERTY);
        require(raw != null && !raw.isBlank(), "launch contract did not supply " + EXPECTED_GAME_ROOT_PROPERTY);
        return Path.of(raw).toAbsolutePath().normalize();
    }

    /** Package-visible for direct, server-free unit testing of the root-binding contract. */
    static Path gameRootFromWorldRoot(Path world, Path expectedGameRoot) {
        Path normalizedWorld = world.toAbsolutePath().normalize();
        Path normalizedExpected = expectedGameRoot.toAbsolutePath().normalize();
        require(Files.isDirectory(normalizedWorld, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalizedWorld),
                "world root must be a real, non-symlinked directory: " + normalizedWorld);
        require("world".equals(normalizedWorld.getFileName().toString()),
                "world root does not have the expected level-name directory name: " + normalizedWorld);
        Path game = normalizedWorld.getParent();
        require(game != null, "world root has no parent");
        require(Files.isDirectory(game, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(game),
                "game root must be a real, non-symlinked directory: " + game);
        // The decisive confinement check: not merely a plausible name/shape,
        // but the exact root the launch contract itself registered.
        require(game.equals(normalizedExpected),
                "game root " + game + " does not match the exact root supplied by the launch contract: " + normalizedExpected);
        require(normalizedWorld.equals(game.resolve("world")), "world root is not canonically game root's own 'world' child");
        for (Path ancestor = game; ancestor != null; ancestor = ancestor.getParent()) {
            require(!Files.isSymbolicLink(ancestor), "a symlinked ancestor is not permitted: " + ancestor);
        }
        return game;
    }

    private static String require(String value, String message) {
        require(value != null, message);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
