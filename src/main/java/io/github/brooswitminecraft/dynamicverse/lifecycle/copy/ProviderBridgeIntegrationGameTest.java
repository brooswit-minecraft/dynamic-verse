package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reviewed-provider integration fixture. It is registered only by the dedicated
 * providerBridgeGameTest run; do not execute without a serialized assignment.
 */
public final class ProviderBridgeIntegrationGameTest {
    private static final ResourceLocation SOURCE_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", "provider_bridge_source");
    private static final ResourceLocation DESTINATION_ID = ResourceLocation.fromNamespaceAndPath("dynamicverse", "provider_bridge_destination");
    private static final BlockPos MARKER = new BlockPos(1, 64, 1);

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 160)
    @PrefixGameTestTemplate(false)
    public void closesCopiesAndRestoresOwnedSource(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        ResourceKey<Level> sourceKey = ResourceKey.create(Registries.DIMENSION, SOURCE_ID);
        ResourceKey<Level> destinationKey = ResourceKey.create(Registries.DIMENSION, DESTINATION_ID);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path sourcePath = DimensionType.getStorageFolder(sourceKey, root).toAbsolutePath().normalize();
        Path destinationPath = DimensionType.getStorageFolder(destinationKey, root).toAbsolutePath().normalize();
        requireFresh(registry, server, sourceKey, destinationKey, sourcePath, destinationPath);

        DynamicDimensionProperties sourceProperties = properties();
        ServerLevel original = requireNonNull(registry.createDynamicDimension(SOURCE_ID,
                server.overworld().getChunkSource().getGenerator(), freshType(), sourceProperties), "source create");
        original.setBlock(MARKER, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        OwnedDimensionDescriptor source = new OwnedDimensionDescriptor(sourceKey, original.getChunkSource().getGenerator(),
                original.dimensionType(), sourceProperties);
        OwnedDimensionDescriptor destination = new OwnedDimensionDescriptor(destinationKey, original.getChunkSource().getGenerator(),
                freshType(), properties());
        OwnedDimensionCatalog catalog = new OwnedDimensionCatalog();
        catalog.register(source);
        catalog.register(destination);
        VerseOperationGate gate = new VerseOperationGate();
        ProviderDuplicationBridge bridge = ProviderDuplicationBridge.forMinecraftServer(server, catalog, gate);
        bridge.registerReady(source.id(), identity(original), 1);

        AtomicReference<ProviderDuplicationBridge.BridgeOutcome> completion = new AtomicReference<>();
        require(bridge.beginDuplicate(source.id(), destination.id(), completion::set)
                == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "provider close admission");
        require(gate.tryAcquireReadyRead(source.id()) == null, "source read must close at admission");
        helper.runAfterDelay(1, () -> awaitTerminal(helper, server, gate, source, destination, original, sourcePath, destinationPath, completion, 0));
    }

    private static void awaitTerminal(GameTestHelper helper, MinecraftServer server, VerseOperationGate gate,
                                      OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination, ServerLevel original,
                                      Path sourcePath, Path destinationPath,
                                      AtomicReference<ProviderDuplicationBridge.BridgeOutcome> completion, int waited) {
        if (completion.get() == null) {
            if (waited >= 120) throw new AssertionError("provider close did not publish a terminal outcome");
            helper.runAfterDelay(1, () -> awaitTerminal(helper, server, gate, source, destination, original, sourcePath, destinationPath, completion, waited + 1));
            return;
        }
        require(completion.get() == ProviderDuplicationBridge.BridgeOutcome.RESTORED, "successful close/copy/restore outcome");
        ServerLevel restored = requireNonNull(server.getLevel(source.key()), "restored source level");
        require(restored != original, "restoration must produce a new source instance");
        require(restored.getBlockState(MARKER).is(Blocks.REDSTONE_LAMP), "persisted source marker");
        VerseOperationGate.ReadyRead ready = gate.tryAcquireReadyRead(source.id());
        require(ready != null && ready.identity().equals(identity(restored)), "restored identity/readiness");
        ready.close();
        require(server.getLevel(destination.key()) == null, "destination remains unloaded");
        require(Files.isDirectory(destinationPath, LinkOption.NOFOLLOW_LINKS), "copied destination directory");
        require(Files.isDirectory(destinationPath.resolve("region"), LinkOption.NOFOLLOW_LINKS), "copied destination region data");
        require(Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS), "source directory retained");
        helper.succeed();
    }

    private static void requireFresh(DynamicDimensionRegistry registry, MinecraftServer server, ResourceKey<Level> source,
                                     ResourceKey<Level> destination, Path sourcePath, Path destinationPath) {
        require(!registry.anyDimensionExists(source.location()) && !registry.anyDimensionExists(destination.location()), "owned key already exists");
        require(server.getLevel(source) == null && server.getLevel(destination) == null, "owned key already loaded");
        require(!Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) && !Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS),
                "existing source/destination path is refused");
    }

    private static String identity(ServerLevel level) {
        return level.dimension().location() + "@" + Integer.toHexString(System.identityHashCode(level));
    }

    private static DynamicDimensionProperties properties() {
        return new DynamicDimensionProperties() {
            public int priority() { return 0; } public Vector3f baseGravity() { return new Vector3f(); }
            public double basePressure() { return 0; } public float universalDrag() { return 0; } public Vector3f magneticNorth() { return new Vector3f(); }
        };
    }

    private static DimensionType freshType() {
        return new DimensionType(OptionalLong.empty(), true, false, false, true, 1.0, false, false,
                -64, 384, 384, BlockTags.INFINIBURN_OVERWORLD, BuiltinDimensionTypes.OVERWORLD_EFFECTS, 0.0F,
                new DimensionType.MonsterSettings(false, true, UniformInt.of(0, 7), 0));
    }

    private static <T> T requireNonNull(T value, String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
