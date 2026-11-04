package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Phase B only: a new JVM reads Phase-A's real state file and drives production replay. */
public final class ProviderSeededRestartPhaseBGameTest {
    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public void reloadPersistedSeededReadyAfterFreshJvm(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        ServerVerseRuntime runtime = null;
        try {
            Path statePath = root.resolve("data").resolve("dynamicverse-managed-state.properties");
            ProviderSeededRestartPhaseAGameTest.require(Files.isRegularFile(statePath, LinkOption.NOFOLLOW_LINKS),
                    "phase B requires Phase-A's retained state file");
            ProviderSeededRestartPhaseAGameTest.require(OwnedCreateStorageConfinement.isExistingConfined(root, ProviderSeededRestartPhaseAGameTest.KEY)
                            && server.getLevel(ProviderSeededRestartPhaseAGameTest.KEY) == null,
                    "phase B starts in a new JVM with retained data but no current level");
            ManagedVerseStateStore.State beforeRecovery = ManagedVerseStateFileStore.minecraft(server).read();
            ManagedVerseStateStore.ReadyVerse ready = ProviderSeededRestartPhaseAGameTest.requireNonNull(
                    beforeRecovery.ready().get(ProviderSeededRestartPhaseAGameTest.ID.toString()), "phase B retained READY descriptor");
            ProviderSeededRestartPhaseAGameTest.require(ready.requestedSeed().equals(OptionalLong.of(ProviderSeededRestartPhaseAGameTest.SEED))
                            && ready.effectiveSeed() == ProviderSeededRestartPhaseAGameTest.SEED && ready.generation() > 0
                            && beforeRecovery.operations().isEmpty(),
                    "phase B consumes exact retained requested/effective seed provenance, not synthetic state");
            runtime = ServerVerseRuntime.create(server);
            ProviderSeededRestartPhaseAGameTest.require(runtime.listManaged().stream().anyMatch(entry -> entry.name().equals(ready.id())
                            && entry.state() == ManagedVerseListing.State.PENDING),
                    "seed-bearing reload return is still excluded from READY before the later observation tick");
            AtomicReference<VerseReservationSnapshot> before = new AtomicReference<>();
            runtime.beginAcquireReadyRead(ProviderSeededRestartPhaseAGameTest.NAME, before::set);
            runtime.tick();
            ProviderSeededRestartPhaseAGameTest.require(before.get() != null && before.get().lease().isEmpty(),
                    "travel is refused before replay observation publishes readiness");
            ServerVerseRuntime ownedRuntime = runtime;
            helper.runAfterDelay(1, () -> awaitReplay(helper, ownedRuntime, server, ready, 80));
        } catch (RuntimeException failure) {
            if (runtime != null) runtime.close();
            helper.fail("phase B setup failed: " + failure);
        }
    }

    private static void awaitReplay(GameTestHelper helper, ServerVerseRuntime runtime, MinecraftServer server,
                                    ManagedVerseStateStore.ReadyVerse ready, int remaining) {
        try {
            runtime.tick();
            ManagedVerseListing listing = runtime.listManaged().stream().filter(entry -> entry.name().equals(ready.id())).findFirst().orElse(null);
            if (listing == null || listing.state() != ManagedVerseListing.State.READY) {
                if (remaining == 0) throw new IllegalStateException("seeded replay did not publish after bounded later ticks");
                helper.runAfterDelay(1, () -> awaitReplay(helper, runtime, server, ready, remaining - 1));
                return;
            }
            ServerLevel reloaded = ProviderSeededRestartPhaseAGameTest.requireNonNull(server.getLevel(ProviderSeededRestartPhaseAGameTest.KEY),
                    "phase B current seeded registration");
            ProviderSeededRestartPhaseAGameTest.require(reloaded.getSeed() == ProviderSeededRestartPhaseAGameTest.SEED
                            && reloaded.getBlockState(ProviderSeededRestartPhaseAGameTest.SENTINEL).equals(Blocks.REDSTONE_LAMP.defaultBlockState())
                            && runtime.recordedEffectiveSeed(ready.id()).equals(OptionalLong.of(ProviderSeededRestartPhaseAGameTest.SEED)),
                    "production seeded reload retains the phase-A world evidence and exact effective seed");
            AtomicReference<VerseReservationSnapshot> after = new AtomicReference<>();
            runtime.beginAcquireReadyRead(ProviderSeededRestartPhaseAGameTest.NAME, after::set); runtime.tick();
            ReadyVerseLease lease = ProviderSeededRestartPhaseAGameTest.requireNonNull(after.get().lease().orElse(null), "phase B READY read lease");
            try {
                ProviderSeededRestartPhaseAGameTest.require(runtime.resolve(lease) == reloaded && lease.generation() == ready.generation()
                                && listing.generation().orElseThrow() == ready.generation(),
                        "fresh Runtime resolves only the later observed current instance at the retained generation");
            } finally { lease.close(); runtime.close(); }
            helper.succeed();
        } catch (RuntimeException failure) {
            runtime.close();
            helper.fail("phase B replay failed: " + failure);
        }
    }
}
