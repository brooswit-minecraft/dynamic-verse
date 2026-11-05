package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Properties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Two-JVM safe-delete crash-recovery fixture (prepared for review; not yet
 * executed — see docs/real-provider-safe-delete-acceptance-plan-20261104.md).
 *
 * <p>Phase A creates a real target and a real unrelated verse through
 * production {@link ServerVerseRuntime#create(MinecraftServer)} wiring
 * (real provider, real {@link ManagedVerseStateFileStore}, real
 * {@link ServerVerseRuntime.DeleteGateway#minecraft}), admits a real
 * confirmed delete, verifies the durable PENDING delete intent is actually
 * on disk, publishes an ownership marker (this exact JVM's own PID plus a
 * nonce) only after that verification, then blocks the server thread
 * forever. {@code beginQuiesceAndClose} only enqueues a ticket; the real
 * close drains on a later {@code tickServer}-injected hook. Never returning
 * from this method, before the server ever reaches its tick loop, is a
 * deterministic barrier: the admitted delete provably never reaches a
 * terminal, because the server thread this runs on is the only thread that
 * could ever drive that drain. This class never kills itself; an external
 * wrapper owns that, against the exact PID this marker reports.</p>
 *
 * <p>Phase B is a second, independently launched JVM against the same
 * confined root. Production {@code ServerVerseRuntime.create}'s constructor
 * already recovers durable state (quarantining the still-PENDING delete
 * intent) before this fixture's own {@link #started} runs; Phase B only
 * observes and records that recovery, then requests a normal graceful
 * stop — Phase B is not itself a crash.</p>
 */
public final class ProviderSafeDeleteCrashFixture {
    public static final String TARGET_NAME = "crash_delete_target";
    public static final ResourceLocation TARGET_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", TARGET_NAME);
    public static final String UNRELATED_NAME = "crash_unrelated";
    public static final ResourceLocation UNRELATED_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", UNRELATED_NAME);
    private static final String MARKER_FILE = "dynamicverse-crash-delete-phase-a-marker.properties";
    private static final String RESULT_FILE = "dynamicverse-crash-delete-phase-b-result.properties";

    private final String phase;

    public ProviderSafeDeleteCrashFixture(String phase) {
        if (!phase.equals("A") && !phase.equals("B")) throw new IllegalArgumentException("unsupported safe-delete crash phase: " + phase);
        this.phase = phase;
    }

    public void started(MinecraftServer server, ServerVerseRuntime runtime) {
        if (phase.equals("A")) phaseA(server, runtime); else phaseB(server, runtime);
    }

    private void phaseA(MinecraftServer server, ServerVerseRuntime runtime) {
        Path root = confinedRoot(server);
        require(!Files.exists(root.resolve(MARKER_FILE), LinkOption.NOFOLLOW_LINKS), "phase A refuses a retained prior marker");
        require(!Files.exists(root.resolve(RESULT_FILE), LinkOption.NOFOLLOW_LINKS), "phase A refuses a retained prior phase B result");

        require(runtime.beginCreate(TARGET_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "phase A target create admitted");
        require(runtime.beginCreate(UNRELATED_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), ignored -> { })
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "phase A unrelated create admitted");
        runtime.tick();
        require(isReady(runtime, TARGET_ID), "target READY before delete");
        require(isReady(runtime, UNRELATED_ID), "unrelated READY before delete");

        require(runtime.beginConfirmedDelete(TARGET_ID.toString(), ignored -> {
                    throw new IllegalStateException("phase A must never observe a delete terminal: the barrier below is supposed to prevent one");
                }) == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                "confirmed delete admitted");

        ManagedVerseStateStore.State durable = ManagedVerseStateFileStore.minecraft(server).read();
        require(durable.operations().values().stream().anyMatch(op -> op.destination().equals(TARGET_ID.toString())
                        && op.kind() == ManagedVerseStateStore.OperationKind.DELETE
                        && op.phase() == ManagedVerseStateStore.OperationPhase.PENDING),
                "durable PENDING delete intent is actually readable from the real state file before any marker is published");

        writeMarker(root);

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
        Path root = confinedRoot(server);
        require(Files.exists(root.resolve(MARKER_FILE), LinkOption.NOFOLLOW_LINKS), "phase B requires phase A's marker to still be present");
        require(!Files.exists(root.resolve(RESULT_FILE), LinkOption.NOFOLLOW_LINKS), "phase B refuses a retained prior result");

        boolean targetQuarantined = runtime.listManaged().stream()
                .anyMatch(item -> item.name().equals(TARGET_ID.toString()) && item.state() == ManagedVerseListing.State.QUARANTINED);
        boolean targetReadableNow = false;
        VerseReservationSnapshot[] read = new VerseReservationSnapshot[1];
        runtime.beginAcquireReadyRead(TARGET_ID.toString(), result -> read[0] = result);
        runtime.tick();
        if (read[0] != null && read[0].lease().isPresent()) { targetReadableNow = true; read[0].lease().get().close(); }
        boolean unrelatedReady = isReady(runtime, UNRELATED_ID);

        require(targetQuarantined, "the interrupted delete's target must be quarantined after recovery, never silently dropped from the catalog");
        require(!targetReadableNow, "the quarantined interrupted-delete target must never be readable after recovery");
        require(unrelatedReady, "the unrelated verse must still replay normally after recovery");

        Path targetDir = root.resolve("dimensions").resolve("dynamicverse").resolve(TARGET_NAME);
        boolean targetStorageIntact = Files.isDirectory(targetDir, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(targetDir);
        require(targetStorageIntact, "an interrupted delete must never have removed the target's on-disk storage");

        writeResult(root, targetQuarantined, targetReadableNow, unrelatedReady, targetStorageIntact);
        server.halt(false);
    }

    private static boolean isReady(ServerVerseRuntime runtime, ResourceLocation id) {
        return runtime.listManaged().stream().anyMatch(item -> item.name().equals(id.toString()) && item.state() == ManagedVerseListing.State.READY);
    }

    private void writeMarker(Path root) {
        Properties properties = new Properties();
        properties.setProperty("pid", Long.toString(ProcessHandle.current().pid()));
        properties.setProperty("nonce", java.util.UUID.randomUUID().toString());
        properties.setProperty("durablePendingVerified", "true");
        writeProperties(root.resolve(MARKER_FILE), properties);
    }

    private void writeResult(Path root, boolean targetQuarantined, boolean targetReadableNow, boolean unrelatedReady, boolean targetStorageIntact) {
        Properties properties = new Properties();
        properties.setProperty("targetQuarantined", Boolean.toString(targetQuarantined));
        properties.setProperty("targetReadableNow", Boolean.toString(targetReadableNow));
        properties.setProperty("unrelatedReady", Boolean.toString(unrelatedReady));
        properties.setProperty("targetStorageIntact", Boolean.toString(targetStorageIntact));
        writeProperties(root.resolve(RESULT_FILE), properties);
    }

    private static void writeProperties(Path file, Properties properties) {
        try {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("refuses to overwrite an existing receipt: " + file);
            try (OutputStream out = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                properties.store(out, "Dynamic Verse safe-delete crash fixture receipt");
            }
        } catch (IOException bad) { throw new IllegalStateException("cannot write receipt " + file, bad); }
    }

    /** Package-visible so an external wrapper's own verification can read the same shape without re-deriving it. */
    public static Properties readProperties(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { properties.load(in); }
        catch (IOException bad) { throw new IllegalStateException("cannot read receipt " + file, bad); }
        return properties;
    }

    private static Path confinedRoot(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root), "crash fixture root must be a real directory");
        return root;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
