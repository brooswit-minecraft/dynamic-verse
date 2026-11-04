package io.github.brooswitminecraft.dynamicverse.lifecycle;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.PlayerRemover;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseResult;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseStatus;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Phase A only: create and close the retained, explicitly seeded native world. */
public final class ProviderSeededRestartPhaseAGameTest {
    static final String NAME = "native_seeded_restart";
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", NAME);
    static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ID);
    static final long SEED = 9_123_417_733L;
    static final BlockPos SENTINEL = new BlockPos(8, 80, 8);

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 240)
    @PrefixGameTestTemplate(false)
    public void createSeededNormalPersistAndClose(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        ServerVerseRuntime runtime = ServerVerseRuntime.create(server);
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> terminal = new AtomicReference<>();
        AtomicInteger callbacks = new AtomicInteger();
        try {
            require(!registry.anyDimensionExists(ID) && registry.canCreateDimension(ID) && server.getLevel(KEY) == null,
                    "phase A needs a unique unused owned key");
            require(OwnedCreateStorageConfinement.isFreshConfined(root, KEY), "phase A needs an absent confined destination path");
            require(!Files.exists(root.resolve("data").resolve("dynamicverse-managed-state.properties"), LinkOption.NOFOLLOW_LINKS),
                    "phase A must not overwrite retained managed state");
            require(runtime.beginCreate(NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.of(SEED), outcome -> {
                callbacks.incrementAndGet(); terminal.compareAndSet(null, outcome);
            }) == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "explicit-seed create is admitted through production Runtime");
            helper.runAfterDelay(1, () -> awaitReady(helper, runtime, server, registry, root, terminal, callbacks, 80));
        } catch (RuntimeException failure) {
            runtime.close();
            helper.fail("phase A setup failed: " + failure);
        }
    }

    private static void awaitReady(GameTestHelper helper, ServerVerseRuntime runtime, MinecraftServer server,
                                   DynamicDimensionRegistry registry, Path root,
                                   AtomicReference<VerseLifecycleCommandService.CreateOutcome> terminal,
                                   AtomicInteger callbacks, int remaining) {
        try {
            runtime.tick();
            if (terminal.get() == null) {
                if (remaining == 0) throw new IllegalStateException("seeded create never reached a terminal outcome");
                helper.runAfterDelay(1, () -> awaitReady(helper, runtime, server, registry, root, terminal, callbacks, remaining - 1));
                return;
            }
            require(terminal.get() == VerseLifecycleCommandService.CreateOutcome.READY && callbacks.get() == 1,
                    "phase A has one READY terminal outcome");
            ServerLevel created = requireNonNull(server.getLevel(KEY), "phase A current registration");
            require(created.getSeed() == SEED && created.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                    "production create used retained explicit seed and canonical NORMAL generator");
            ManagedVerseStateStore.State state = ManagedVerseStateFileStore.minecraft(server).read();
            ManagedVerseStateStore.ReadyVerse ready = requireNonNull(state.ready().get(ID.toString()), "phase A durable READY");
            require(ready.requestedSeed().equals(OptionalLong.of(SEED)) && ready.effectiveSeed() == SEED && ready.generation() > 0
                            && state.operations().isEmpty(),
                    "phase A atomically retains requested/effective seed and removes CREATE intent before close");
            created.setBlock(SENTINEL, Blocks.REDSTONE_LAMP.defaultBlockState(), 0);
            AtomicReference<VerseReservationSnapshot> read = new AtomicReference<>();
            runtime.beginAcquireReadyRead(NAME, read::set); runtime.tick();
            ReadyVerseLease lease = requireNonNull(requireNonNull(read.get(), "phase A read result").lease().orElse(null), "phase A read lease");
            try { require(runtime.resolve(lease) == created, "phase A read resolves the created instance"); }
            finally { lease.close(); }
            // Stop the fixture-owned Runtime before external close; no stale fixture tick or lease remains after this point.
            runtime.close();
            AtomicReference<QuiesceAndCloseResult> close = new AtomicReference<>();
            AtomicInteger closeCallbacks = new AtomicInteger();
            require(registry.beginQuiesceAndClose(ID, PlayerRemover.DEFAULT, receipt -> {
                closeCallbacks.incrementAndGet(); close.compareAndSet(null, receipt);
            }), "phase A close admission");
            helper.runAfterDelay(1, () -> awaitClose(helper, server, close, closeCallbacks, 80));
        } catch (RuntimeException failure) {
            runtime.close();
            helper.fail("phase A observation failed: " + failure);
        }
    }

    private static void awaitClose(GameTestHelper helper, MinecraftServer server, AtomicReference<QuiesceAndCloseResult> close,
                                   AtomicInteger callbacks, int remaining) {
        QuiesceAndCloseResult receipt = close.get();
        if (receipt == null) {
            if (remaining == 0) { helper.fail("phase A close did not produce a terminal receipt"); return; }
            helper.runAfterDelay(1, () -> awaitClose(helper, server, close, callbacks, remaining - 1));
            return;
        }
        require(callbacks.get() == 1 && receipt.key().equals(KEY) && receipt.status() == QuiesceAndCloseStatus.SAVED_AND_CLOSED
                        && receipt.failureStage() == null && server.getLevel(KEY) == null,
                "phase A retains only a successful forced-save-and-close receipt and no loaded source");
        helper.succeed();
    }

    static <T> T requireNonNull(T value, String message) {
        if (value == null) throw new IllegalStateException(message);
        return value;
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
