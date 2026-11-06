package io.github.brooswitminecraft.dynamicverse.lifecycle;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseResult;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseStatus;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Native acceptance uses only its fresh dedicated GameTest world and never deletes any data. */
public final class ProviderExplicitSuperflatGameTest {
    private static final String NAME = "explicit_flat_review";
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", NAME);
    private static final ResourceKey<Level> KEY = ResourceKey.create(Registries.DIMENSION, ID);

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 360)
    @PrefixGameTestTemplate(false)
    public void explicitSuperflatSeedSurvivesConfirmedCloseAndDurableReplay(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var registry = DynamicDimensionRegistry.from(server);
        var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        require(!registry.anyDimensionExists(ID) && server.getLevel(KEY) == null
                && !Files.exists(DimensionType.getStorageFolder(KEY, root), LinkOption.NOFOLLOW_LINKS),
                "fixture must own a new unregistered dimension path");
        long seed = server.getWorldData().worldGenOptions().seed() ^ 0x5A17C0DEL;
        AtomicReference<ServerVerseRuntime> runtime = new AtomicReference<>(ServerVerseRuntime.create(server));
        AtomicInteger callbacks = new AtomicInteger();
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> outcome = new AtomicReference<>();
        AtomicReference<QuiesceAndCloseResult> close = new AtomicReference<>();
        AtomicInteger closeCallbacks = new AtomicInteger();
        AtomicReference<ServerLevel> original = new AtomicReference<>();
        int[] phase = {0};
        require(runtime.get().beginCreate(NAME, VerseLifecycleCommandService.CreateType.SUPERFLAT, OptionalLong.of(seed), result -> {
            callbacks.incrementAndGet(); outcome.set(result);
        }) == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "production explicit SUPERFLAT create admitted");
        helper.startSequence().thenWaitUntil(() -> {
            runtime.get().tick();
            if (phase[0] == 0) {
                helper.assertTrue(outcome.get() != null, "waiting for SUPERFLAT create terminal");
                require(outcome.get() == VerseLifecycleCommandService.CreateOutcome.READY && callbacks.get() == 1,
                        "SUPERFLAT create reaches exactly one READY terminal");
                ServerLevel level = server.getLevel(KEY);
                verify(helper, runtime.get(), level, seed);
                original.set(level);
                require(registry.beginQuiesceAndClose(ID, (owner, player) -> {
                    throw new IllegalStateException("test-owned empty level unexpectedly gained a player");
                }, result -> { closeCallbacks.incrementAndGet(); close.set(result); }), "confirmed-close admission");
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                helper.assertTrue(close.get() != null, "waiting for exact forced-save-and-close terminal");
                require(closeCallbacks.get() == 1 && close.get().key().equals(KEY)
                        && close.get().initiallyLoaded() && !close.get().sourceStillLoaded()
                        && close.get().status() == QuiesceAndCloseStatus.SAVED_AND_CLOSED
                        && close.get().failureStage() == null && close.get().cause() == null
                        && server.getLevel(KEY) == null, "exact successful close and absent live registration: " + close.get() + " loaded=" + (server.getLevel(KEY) != null));
                runtime.get().close();
                runtime.set(ServerVerseRuntime.create(server));
                phase[0] = 2;
            }
            runtime.get().tick();
            helper.assertTrue(runtime.get().listManaged().stream().anyMatch(value -> value.name().equals(ID.toString())
                    && value.state() == ManagedVerseListing.State.READY), "waiting for observed durable SUPERFLAT replay");
            ServerLevel replayed = server.getLevel(KEY);
            require(replayed != original.get(), "replay reconstructs a fresh live instance");
            verify(helper, runtime.get(), replayed, seed);
            System.out.println("EXPLICIT_SUPERFLAT_ACCEPTED seed=" + seed + " createCallbacks=" + callbacks.get()
                    + " closeCallbacks=" + closeCallbacks.get() + " freshReplay=true");
        }).thenExecute(() -> runtime.get().close()).thenSucceed();
    }

    private static void verify(GameTestHelper helper, ServerVerseRuntime runtime, ServerLevel level, long seed) {
        require(level != null && level.getSeed() == seed, "actual level uses the exact requested seed");
        var defaults = FlatLevelGeneratorSettings.getDefault(helper.getLevel().getServer().registryAccess().lookupOrThrow(Registries.BIOME),
                helper.getLevel().getServer().registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),
                helper.getLevel().getServer().registryAccess().lookupOrThrow(Registries.PLACED_FEATURE));
        require(level.getChunkSource().getGenerator() instanceof FlatLevelSource flat
                && ProviderCreateIntegrationGameTest.sameDefaultSuperflat(flat.settings(), defaults),
                "actual generator retains every default SUPERFLAT layer/biome/feature/structure setting");
        var durable = ManagedVerseStateFileStore.minecraft(helper.getLevel().getServer()).read().ready().get(ID.toString());
        require(durable != null && durable.generator() == VerseLifecycleCommandService.CreateType.SUPERFLAT
                && durable.requestedSeed().orElseThrow() == seed && durable.effectiveSeed() == seed,
                "durable descriptor preserves exact SUPERFLAT provenance and requested/effective seed");
        AtomicReference<VerseReservationSnapshot> acquired = new AtomicReference<>();
        runtime.beginAcquireReadyRead(NAME, acquired::set); runtime.tick();
        require(acquired.get() != null && acquired.get().lease().isPresent(), "replayed ready boundary grants a lease");
        try (ReadyVerseLease lease = acquired.get().lease().orElseThrow()) {
            require(runtime.resolve(lease) == level, "the lease resolves only the exact current SUPERFLAT instance");
        }
    }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
