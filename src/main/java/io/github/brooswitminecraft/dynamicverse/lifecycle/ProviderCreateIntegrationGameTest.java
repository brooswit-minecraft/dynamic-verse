package io.github.brooswitminecraft.dynamicverse.lifecycle;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService.CreateAdmission;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService.CreateOutcome;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService.CreateType;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Dedicated native provider create proof.  It uses only ServerVerseRuntime's
 * production CreateGateway and observes publication from later GameTest ticks.
 * Do not register or execute it outside the providerCreateGameTest run.
 */
public final class ProviderCreateIntegrationGameTest {
    private static final String NORMAL_NAME = "native_create_normal";
    private static final String SUPERFLAT_NAME = "native_create_superflat";
    private static final ResourceLocation NORMAL_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", NORMAL_NAME);
    private static final ResourceLocation SUPERFLAT_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", SUPERFLAT_NAME);

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 200)
    @PrefixGameTestTemplate(false)
    public void createsOmittedSeedNormalThenDefaultSuperflat(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerVerseRuntime runtime = ServerVerseRuntime.create(server);
        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        ResourceKey<Level> normalKey = ResourceKey.create(Registries.DIMENSION, NORMAL_ID);
        ResourceKey<Level> superflatKey = ResourceKey.create(Registries.DIMENSION, SUPERFLAT_ID);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Fixture fixture = new Fixture(runtime, server, registry, normalKey, superflatKey, root,
                server.getWorldData().worldGenOptions().seed());
        try {
            requireFresh(helper, fixture, normalKey, NORMAL_ID);
            requireFresh(helper, fixture, superflatKey, SUPERFLAT_ID);

            AtomicInteger suppliedSeedCompletions = new AtomicInteger();
            require(helper, runtime.beginCreate(NORMAL_NAME, CreateType.NORMAL, OptionalLong.of(7L),
                    ignored -> suppliedSeedCompletions.incrementAndGet()) == CreateAdmission.ERR_PER_VERSE_SEED_UNSUPPORTED,
                    "supplied seed is rejected before provider mutation");
            require(helper, suppliedSeedCompletions.get() == 0 && server.getLevel(normalKey) == null
                    && !registry.anyDimensionExists(NORMAL_ID) && fresh(root, normalKey),
                    "supplied seed rejection left no registration or path");

            require(helper, runtime.listManaged().isEmpty(), "fresh runtime has no adopted catalog entries");
            require(helper, runtime.beginCreate(NORMAL_NAME, CreateType.NORMAL, OptionalLong.empty(), outcome -> {
                        fixture.normalCount.incrementAndGet();
                        fixture.normalCompletion.set(outcome);
                    })
                    == CreateAdmission.ADMITTED, "normal omitted-seed admission");
            require(helper, fixture.normalCompletion.get() == null && listing(runtime, normalKey.location().toString()).state()
                    == ManagedVerseListing.State.PENDING, "normal stays pending until a later lifecycle tick");
            helper.runAfterDelay(1, () -> observeNormal(helper, fixture, 0));
        } catch (RuntimeException failure) {
            fail(helper, fixture, "native create setup failed: " + failure);
        }
    }

    private static void observeNormal(GameTestHelper helper, Fixture fixture, int waited) {
        try {
            fixture.runtime.tick();
            if (fixture.normalCompletion.get() == null) {
                if (waited >= 80) { fail(helper, fixture, "normal create did not reach a terminal outcome"); return; }
                helper.runAfterDelay(1, () -> observeNormal(helper, fixture, waited + 1));
                return;
            }
            require(helper, fixture.normalCompletion.get() == CreateOutcome.READY && fixture.normalCount.get() == 1,
                    "normal completed READY exactly once");
            ServerLevel normal = requireNonNull(helper, fixture.server.getLevel(fixture.normalKey), "normal returned/current registration");
            fixture.normal = normal;
            verifyNormal(helper, fixture, normal);

            AtomicInteger collisionCompletions = new AtomicInteger();
            require(helper, fixture.runtime.beginCreate(NORMAL_NAME, CreateType.NORMAL, OptionalLong.empty(),
                    ignored -> collisionCompletions.incrementAndGet()) == CreateAdmission.ERR_VERSE_ALREADY_EXISTS,
                    "production catalog/provider collision guard");
            require(helper, collisionCompletions.get() == 0 && fixture.server.getLevel(fixture.superflatKey) == null
                    && !fixture.registry.anyDimensionExists(SUPERFLAT_ID) && fresh(fixture.root, fixture.superflatKey),
                    "collision refusal did not create or orphan the next owned key");

            require(helper, fixture.runtime.beginCreate(SUPERFLAT_NAME, CreateType.SUPERFLAT, OptionalLong.empty(), outcome -> {
                        fixture.superflatCount.incrementAndGet();
                        fixture.superflatCompletion.set(outcome);
                    }) == CreateAdmission.ADMITTED, "default superflat omitted-seed admission");
            require(helper, fixture.superflatCompletion.get() == null && listing(fixture.runtime, fixture.superflatKey.location().toString()).state()
                    == ManagedVerseListing.State.PENDING, "superflat stays pending until a later lifecycle tick");
            helper.runAfterDelay(1, () -> observeSuperflat(helper, fixture, 0));
        } catch (RuntimeException failure) {
            fail(helper, fixture, "normal create observation failed: " + failure);
        }
    }

    private static void observeSuperflat(GameTestHelper helper, Fixture fixture, int waited) {
        try {
            fixture.runtime.tick();
            if (fixture.superflatCompletion.get() == null) {
                if (waited >= 80) { fail(helper, fixture, "superflat create did not reach a terminal outcome"); return; }
                helper.runAfterDelay(1, () -> observeSuperflat(helper, fixture, waited + 1));
                return;
            }
            require(helper, fixture.superflatCompletion.get() == CreateOutcome.READY && fixture.superflatCount.get() == 1,
                    "superflat completed READY exactly once");
            ServerLevel superflat = requireNonNull(helper, fixture.server.getLevel(fixture.superflatKey), "superflat returned/current registration");
            verifySuperflat(helper, fixture, superflat);
            require(helper, fixture.normal.dimensionType() != superflat.dimensionType(),
                    "each create retains a distinct supported DimensionType identity");
            verifyListingsAndBareAccess(helper, fixture, superflat);
            helper.runAfterDelay(1, () -> finish(helper, fixture));
        } catch (RuntimeException failure) {
            fail(helper, fixture, "superflat create observation failed: " + failure);
        }
    }

    private static void verifyNormal(GameTestHelper helper, Fixture fixture, ServerLevel normal) {
        ChunkGenerator generator = normal.getChunkSource().getGenerator();
        require(helper, generator instanceof NoiseBasedChunkGenerator, "normal uses the canonical noise generator");
        NoiseBasedChunkGenerator noise = (NoiseBasedChunkGenerator) generator;
        require(helper, noise.stable(NoiseGeneratorSettings.OVERWORLD)
                && noise.getBiomeSource() instanceof MultiNoiseBiomeSource source
                && source.stable(MultiNoiseBiomeSourceParameterLists.OVERWORLD),
                "normal retains OVERWORLD settings and biome preset");
        require(helper, normal.getSeed() == fixture.sharedSeed
                && fixture.runtime.recordedEffectiveSeed(fixture.normalKey.location().toString()).orElseThrow() == fixture.sharedSeed,
                "normal records the actual shared save seed");
        require(helper, listing(fixture.runtime, fixture.normalKey.location().toString()).state() == ManagedVerseListing.State.READY,
                "normal is READY only after current registration observation");
    }

    private static void verifySuperflat(GameTestHelper helper, Fixture fixture, ServerLevel superflat) {
        ChunkGenerator generator = superflat.getChunkSource().getGenerator();
        FlatLevelGeneratorSettings defaults = FlatLevelGeneratorSettings.getDefault(
                fixture.server.registryAccess().lookupOrThrow(Registries.BIOME),
                fixture.server.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET),
                fixture.server.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE));
        require(helper, generator instanceof FlatLevelSource flat && sameDefaultSuperflat(flat.settings(), defaults),
                "superflat uses the default FlatLevelSource configuration");
        require(helper, superflat.getSeed() == fixture.sharedSeed
                && fixture.runtime.recordedEffectiveSeed(fixture.superflatKey.location().toString()).orElseThrow() == fixture.sharedSeed,
                "superflat records the actual shared save seed");
        require(helper, listing(fixture.runtime, fixture.superflatKey.location().toString()).state() == ManagedVerseListing.State.READY,
                "superflat is READY only after current registration observation");
    }

    /** Public semantic comparison: FlatLayerInfo has no value equality in the pinned runtime. */
    static boolean sameDefaultSuperflat(FlatLevelGeneratorSettings actual, FlatLevelGeneratorSettings expected) {
        if (!actual.getBiome().equals(expected.getBiome()) || !sameLayers(actual.getLayersInfo(), expected.getLayersInfo())) return false;
        if (!holderKeys(actual.structureOverrides()).equals(holderKeys(expected.structureOverrides()))) return false;
        // adjustGenerationSettings exposes the default decoration/lake result without
        // relying on its private flags or on independently allocated holder lists.
        return featureKeys(actual.adjustGenerationSettings(actual.getBiome()))
                .equals(featureKeys(expected.adjustGenerationSettings(expected.getBiome())));
    }

    private static boolean sameLayers(List<FlatLayerInfo> actual, List<FlatLayerInfo> expected) {
        if (actual.size() != expected.size()) return false;
        for (int index = 0; index < actual.size(); index++) {
            FlatLayerInfo actualLayer = actual.get(index);
            FlatLayerInfo expectedLayer = expected.get(index);
            if (actualLayer.getHeight() != expectedLayer.getHeight()
                    || !actualLayer.getBlockState().equals(expectedLayer.getBlockState())) return false;
        }
        return true;
    }

    private static List<String> holderKeys(Optional<HolderSet<StructureSet>> holders) {
        return holders.stream().flatMap(HolderSet::stream).map(ProviderCreateIntegrationGameTest::holderKey).toList();
    }

    private static List<List<String>> featureKeys(BiomeGenerationSettings settings) {
        return settings.features().stream().map(features -> features.stream()
                .map(ProviderCreateIntegrationGameTest::holderKey).toList()).toList();
    }

    private static <T> String holderKey(Holder<T> holder) {
        return holder.unwrapKey().map(key -> key.location().toString()).orElseGet(() -> holder.value().toString());
    }

    private static void verifyListingsAndBareAccess(GameTestHelper helper, Fixture fixture, ServerLevel superflat) {
        List<ManagedVerseListing> listed = fixture.runtime.listManaged();
        require(helper, listed.size() == 2 && listed.stream().allMatch(entry -> entry.state() == ManagedVerseListing.State.READY
                        && entry.levelIdentity().isPresent() && entry.generation().isPresent()),
                "exactly two owned READY entries expose current identities");
        AtomicReference<VerseReservationSnapshot> acquired = new AtomicReference<>();
        require(helper, fixture.runtime.beginAcquireReadyRead(NORMAL_NAME, acquired::set).phase() == VerseReservationPhase.PENDING,
                "bare normal name enters the shared access facade");
        fixture.runtime.tick();
        VerseReservationSnapshot result = requireNonNull(helper, acquired.get(), "bare access terminal result");
        ReadyVerseLease lease = requireNonNull(helper, result.lease().orElse(null), "bare access lease");
        try {
            require(helper, fixture.runtime.resolve(lease) == fixture.normal, "bare access resolves the exact normal instance");
        } finally {
            lease.close();
        }
        AtomicReference<VerseReservationSnapshot> reacquired = new AtomicReference<>();
        fixture.runtime.beginAcquireReadyRead(SUPERFLAT_NAME, reacquired::set);
        fixture.runtime.tick();
        ReadyVerseLease second = requireNonNull(helper, requireNonNull(helper, reacquired.get(), "post-release access result").lease().orElse(null),
                "lease release permits a later bare superflat read");
        try { require(helper, fixture.runtime.resolve(second) == superflat, "bare superflat resolves current instance"); }
        finally { second.close(); }
    }

    private static ManagedVerseListing listing(ServerVerseRuntime runtime, String key) {
        return runtime.listManaged().stream().filter(entry -> entry.name().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing catalog entry " + key));
    }

    private static void requireFresh(GameTestHelper helper, Fixture fixture, ResourceKey<Level> key, ResourceLocation id) {
        require(helper, !fixture.registry.anyDimensionExists(id) && fixture.registry.canCreateDimension(id)
                && fixture.server.getLevel(key) == null && fresh(fixture.root, key),
                "fresh owned provider key/path " + id);
    }

    private static boolean fresh(Path root, ResourceKey<Level> key) {
        Path path = DimensionType.getStorageFolder(key, root).toAbsolutePath().normalize();
        return OwnedCreateStorageConfinement.isFreshConfined(root, key) && !Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static <T> T requireNonNull(GameTestHelper helper, T value, String message) {
        if (value == null) helper.fail(message);
        return value;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static void finish(GameTestHelper helper, Fixture fixture) {
        try {
            fixture.runtime.tick();
            require(helper, fixture.normalCount.get() == 1 && fixture.superflatCount.get() == 1,
                    "later lifecycle tick did not replay terminal create feedback");
            helper.succeed();
        } finally {
            fixture.runtime.close();
        }
    }

    private static void fail(GameTestHelper helper, Fixture fixture, String message) {
        try { fixture.runtime.close(); }
        finally { helper.fail(message); }
    }

    private static final class Fixture {
        private final ServerVerseRuntime runtime;
        private final MinecraftServer server;
        private final DynamicDimensionRegistry registry;
        private final ResourceKey<Level> normalKey;
        private final ResourceKey<Level> superflatKey;
        private final Path root;
        private final long sharedSeed;
        private final AtomicReference<CreateOutcome> normalCompletion = new AtomicReference<>();
        private final AtomicReference<CreateOutcome> superflatCompletion = new AtomicReference<>();
        private final AtomicInteger normalCount = new AtomicInteger();
        private final AtomicInteger superflatCount = new AtomicInteger();
        private ServerLevel normal;

        private Fixture(ServerVerseRuntime runtime, MinecraftServer server, DynamicDimensionRegistry registry,
                        ResourceKey<Level> normalKey, ResourceKey<Level> superflatKey, Path root, long sharedSeed) {
            this.runtime = runtime;
            this.server = server;
            this.registry = registry;
            this.normalKey = normalKey;
            this.superflatKey = superflatKey;
            this.root = root;
            this.sharedSeed = sharedSeed;
        }
    }
}
