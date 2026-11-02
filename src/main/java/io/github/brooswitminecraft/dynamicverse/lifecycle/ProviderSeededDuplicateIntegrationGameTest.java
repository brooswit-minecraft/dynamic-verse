package io.github.brooswitminecraft.dynamicverse.lifecycle;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Native provider proof for the production Runtime seeded-duplicate path.
 * It is registered only by providerSeededDuplicateGameTest and must receive a
 * separately reviewed serialized runtime assignment.
 */
public final class ProviderSeededDuplicateIntegrationGameTest {
    private static final String SOURCE_NAME = "native_seeded_duplicate_source";
    // Runtime owned-name normalization permits at most 32 characters.
    private static final String DESTINATION_NAME = "native_seeded_dup_destination";
    private static final ResourceLocation SOURCE_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", SOURCE_NAME);
    private static final ResourceLocation DESTINATION_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", DESTINATION_NAME);
    private static final ResourceKey<Level> SOURCE_KEY = ResourceKey.create(Registries.DIMENSION, SOURCE_ID);
    private static final ResourceKey<Level> DESTINATION_KEY = ResourceKey.create(Registries.DIMENSION, DESTINATION_ID);
    private static final long SEED = 9_123_417_733L;
    private static final BlockPos COPIED_MARKER = new BlockPos(8, 80, 8);
    private static final BlockPos DESTINATION_ONLY = new BlockPos(9, 80, 8);
    private static final BlockPos SOURCE_ONLY = new BlockPos(10, 80, 8);

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 300)
    @PrefixGameTestTemplate(false)
    public void duplicatesExplicitSeedAndRestoresIndependentWorlds(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerVerseRuntime runtime = ServerVerseRuntime.create(server);
        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Fixture fixture = new Fixture(runtime, registry, server, root);
        try {
            require(fresh(registry, server, root, SOURCE_ID, SOURCE_KEY), "unique seeded source key/path");
            require(fresh(registry, server, root, DESTINATION_ID, DESTINATION_KEY), "unique seeded destination key/path");
            require(runtime.beginCreate(SOURCE_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.of(SEED), outcome -> {
                fixture.createCallbacks.incrementAndGet(); fixture.createOutcome.compareAndSet(null, outcome);
            }) == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "production explicit-seed source create admission");
            helper.runAfterDelay(1, () -> awaitSource(helper, fixture, 100));
        } catch (RuntimeException failure) {
            fail(helper, fixture, "seeded duplicate setup failed: " + failure);
        }
    }

    private static void awaitSource(GameTestHelper helper, Fixture fixture, int remaining) {
        try {
            fixture.runtime.tick();
            if (fixture.createOutcome.get() == null) {
                if (remaining == 0) throw new IllegalStateException("seeded source create did not reach terminal outcome");
                helper.runAfterDelay(1, () -> awaitSource(helper, fixture, remaining - 1));
                return;
            }
            require(fixture.createOutcome.get() == VerseLifecycleCommandService.CreateOutcome.READY && fixture.createCallbacks.get() == 1,
                    "source has exactly one READY terminal");
            fixture.originalSource = requireNonNull(fixture.server.getLevel(SOURCE_KEY), "created source registration");
            require(fixture.originalSource.getSeed() == SEED, "source exposes admitted explicit seed");
            assertDurableSeed(fixture, SOURCE_ID.toString());
            fixture.originalSource.setBlock(COPIED_MARKER, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
            VerseLifecycleCommandService.DuplicateAdmission admission = fixture.runtime.beginDuplicate(SOURCE_NAME, DESTINATION_NAME, outcome -> {
                fixture.duplicateCallbacks.incrementAndGet(); fixture.duplicateOutcome.compareAndSet(null, outcome);
            });
            require(admission == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED,
                    "production seeded duplicate admission=" + admission);
            require(fixture.runtime.listManaged().stream().anyMatch(entry -> entry.name().equals(DESTINATION_ID.toString())
                    && entry.state() == ManagedVerseListing.State.PENDING), "destination remains non-ready until actual load");
            helper.runAfterDelay(1, () -> awaitDuplicate(helper, fixture, 160));
        } catch (RuntimeException failure) {
            fail(helper, fixture, "seeded source observation failed: " + failure);
        }
    }

    private static void awaitDuplicate(GameTestHelper helper, Fixture fixture, int remaining) {
        try {
            fixture.runtime.tick();
            if (fixture.duplicateOutcome.get() == null) {
                if (remaining == 0) throw new IllegalStateException("seeded duplicate did not reach terminal outcome");
                helper.runAfterDelay(1, () -> awaitDuplicate(helper, fixture, remaining - 1));
                return;
            }
            require(fixture.duplicateOutcome.get() == VerseLifecycleCommandService.DuplicateOutcome.LOADED
                            && fixture.duplicateCallbacks.get() == 1,
                    "duplicate has exactly one LOADED terminal");
            ServerLevel restored = requireNonNull(fixture.server.getLevel(SOURCE_KEY), "restored source registration");
            ServerLevel destination = requireNonNull(fixture.server.getLevel(DESTINATION_KEY), "loaded destination registration");
            require(restored != fixture.originalSource && destination != fixture.originalSource && destination != restored,
                    "source restoration and destination load use distinct live identities");
            require(restored.getSeed() == SEED && destination.getSeed() == SEED,
                    "both provider reloads retain admitted explicit effective seed");
            require(restored.getBlockState(COPIED_MARKER).is(Blocks.REDSTONE_LAMP)
                            && destination.getBlockState(COPIED_MARKER).is(Blocks.REDSTONE_LAMP),
                    "forced-save source marker survives restoration and confined copy");
            assertDurableSeed(fixture, SOURCE_ID.toString());
            assertDurableSeed(fixture, DESTINATION_ID.toString());
            require(fixture.runtime.recordedEffectiveSeed(SOURCE_ID.toString()).equals(OptionalLong.of(SEED))
                            && fixture.runtime.recordedEffectiveSeed(DESTINATION_ID.toString()).equals(OptionalLong.of(SEED)),
                    "runtime retains source and destination effective seeds");
            assertLeaseResolves(fixture, SOURCE_NAME, restored);
            assertLeaseResolves(fixture, DESTINATION_NAME, destination);
            BlockState sourceDestinationBefore = restored.getBlockState(DESTINATION_ONLY);
            BlockState destinationSourceBefore = destination.getBlockState(SOURCE_ONLY);
            require(destination.setBlock(DESTINATION_ONLY, Blocks.GLOWSTONE.defaultBlockState(), 3),
                    "destination independence write succeeds");
            require(restored.setBlock(SOURCE_ONLY, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3),
                    "restored source independence write succeeds");
            require(destination.getBlockState(DESTINATION_ONLY).is(Blocks.GLOWSTONE)
                            && restored.getBlockState(SOURCE_ONLY).is(Blocks.DIAMOND_BLOCK),
                    "each local independence write is observable in its own world");
            require(restored.getBlockState(DESTINATION_ONLY).equals(sourceDestinationBefore)
                            && destination.getBlockState(SOURCE_ONLY).equals(destinationSourceBefore),
                    "opposite worlds retain their pre-write block states");
            helper.succeed();
        } catch (RuntimeException failure) {
            fail(helper, fixture, "seeded duplicate observation failed: " + failure);
        } finally {
            if (fixture.duplicateOutcome.get() != null) fixture.runtime.close();
        }
    }

    private static void assertDurableSeed(Fixture fixture, String key) {
        ManagedVerseStateStore.ReadyVerse ready = ManagedVerseStateFileStore.minecraft(fixture.server).read().ready().get(key);
        require(ready != null && ready.requestedSeed().equals(OptionalLong.of(SEED)) && ready.effectiveSeed() == SEED && ready.generation() > 0,
                "durable requested/effective seed and generation for " + key);
    }

    private static void assertLeaseResolves(Fixture fixture, String name, ServerLevel expected) {
        AtomicReference<VerseReservationSnapshot> result = new AtomicReference<>();
        fixture.runtime.beginAcquireReadyRead(name, result::set);
        fixture.runtime.tick();
        ReadyVerseLease lease = requireNonNull(requireNonNull(result.get(), "read result for " + name).lease().orElse(null), "read lease for " + name);
        try { require(fixture.runtime.resolve(lease) == expected, "ready lease resolves exact current " + name); }
        finally { lease.close(); }
    }

    private static boolean fresh(DynamicDimensionRegistry registry, MinecraftServer server, Path root,
                                 ResourceLocation id, ResourceKey<Level> key) {
        Path path = DimensionType.getStorageFolder(key, root).toAbsolutePath().normalize();
        return !registry.anyDimensionExists(id) && registry.canCreateDimension(id) && server.getLevel(key) == null
                && OwnedCreateStorageConfinement.isFreshConfined(root, key) && !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static <T> T requireNonNull(T value, String message) {
        if (value == null) throw new IllegalStateException(message);
        return value;
    }
    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
    private static void fail(GameTestHelper helper, Fixture fixture, String message) {
        try { fixture.runtime.close(); }
        finally { helper.fail(message); }
    }

    private static final class Fixture {
        private final ServerVerseRuntime runtime;
        private final DynamicDimensionRegistry registry;
        private final MinecraftServer server;
        @SuppressWarnings("unused") private final Path root;
        private final AtomicReference<VerseLifecycleCommandService.CreateOutcome> createOutcome = new AtomicReference<>();
        private final AtomicReference<VerseLifecycleCommandService.DuplicateOutcome> duplicateOutcome = new AtomicReference<>();
        private final AtomicInteger createCallbacks = new AtomicInteger();
        private final AtomicInteger duplicateCallbacks = new AtomicInteger();
        private ServerLevel originalSource;
        private Fixture(ServerVerseRuntime runtime, DynamicDimensionRegistry registry, MinecraftServer server, Path root) {
            this.runtime = runtime; this.registry = registry; this.server = server; this.root = root;
        }
    }
}
