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
import java.util.concurrent.atomic.AtomicInteger;
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
    public void closesCopiesRestoresAndLoadsOwnedDestination(GameTestHelper helper) throws Exception {
        MinecraftServer server = helper.getLevel().getServer();
        DynamicDimensionRegistry registry = DynamicDimensionRegistry.from(server);
        ResourceKey<Level> sourceKey = ResourceKey.create(Registries.DIMENSION, SOURCE_ID);
        ResourceKey<Level> destinationKey = ResourceKey.create(Registries.DIMENSION, DESTINATION_ID);
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path sourcePath = DimensionType.getStorageFolder(sourceKey, root).toAbsolutePath().normalize();
        Path destinationPath = DimensionType.getStorageFolder(destinationKey, root).toAbsolutePath().normalize();
        requireFresh(helper, registry, server, sourceKey, destinationKey, sourcePath, destinationPath);

        DynamicDimensionProperties sourceProperties = properties();
        ServerLevel original = requireNonNull(helper, registry.createDynamicDimension(SOURCE_ID,
                server.overworld().getChunkSource().getGenerator(), freshType(), sourceProperties), "source create");
        helper.runAfterDelay(1, () -> awaitRegistration(helper, server, sourceKey, destinationKey, sourcePath, destinationPath,
                original, sourceProperties, 0));
    }

    private static void awaitRegistration(GameTestHelper helper, MinecraftServer server, ResourceKey<Level> sourceKey,
                                          ResourceKey<Level> destinationKey, Path sourcePath, Path destinationPath,
                                          ServerLevel original, DynamicDimensionProperties sourceProperties, int waited) {
        if (server.getLevel(sourceKey) != original) {
            if (waited >= 40) {
                helper.fail("created source did not register as its returned instance");
                return;
            }
            helper.runAfterDelay(1, () -> awaitRegistration(helper, server, sourceKey, destinationKey, sourcePath, destinationPath,
                    original, sourceProperties, waited + 1));
            return;
        }
        require(helper, original.dimension().equals(sourceKey) && original.players().isEmpty(), "registered source key/players");
        original.setBlock(MARKER, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        OwnedDimensionDescriptor source = new OwnedDimensionDescriptor(sourceKey, original.getChunkSource().getGenerator(),
                original.dimensionType(), sourceProperties);
        OwnedDimensionDescriptor destination = new OwnedDimensionDescriptor(destinationKey, original.getChunkSource().getGenerator(),
                freshType(), properties());
        require(helper, source.type() != destination.type(), "destination requires a distinct provider DimensionType identity");
        OwnedDimensionCatalog catalog = new OwnedDimensionCatalog();
        catalog.register(source);
        catalog.register(destination);
        VerseOperationGate gate = new VerseOperationGate();
        AtomicReference<Path> markerRegion = new AtomicReference<>();
        ClosedDimensionCopier productionCopier = new ClosedDimensionCopier();
        ClosedDimensionCopyPort observingCopier = (request, receipt, fence) -> {
            productionCopier.copy(request, receipt, fence);
            copyRequire(gate.tryAcquireReadyRead(source.id()) == null, "source read released before copied-data observation");
            Path relative = Path.of("region", "r.0.0.mca");
            Path copiedSource = request.storageRoot().resolve(request.sourceRelative()).resolve(relative);
            Path copiedDestination = request.storageRoot().resolve(request.destinationRelative()).resolve(relative);
            copyRequire(Files.isRegularFile(copiedSource, LinkOption.NOFOLLOW_LINKS), "saved marker region source file");
            copyRequire(Files.isRegularFile(copiedDestination, LinkOption.NOFOLLOW_LINKS), "saved marker region destination file");
            try {
                copyRequire(Files.mismatch(copiedSource, copiedDestination) == -1L, "marker-bearing region copied byte-for-byte");
            } catch (java.io.IOException e) {
                throw new ClosedDimensionCopyException("marker-bearing region comparison failed", e);
            }
            markerRegion.set(relative);
        };
        ProviderDuplicationBridge bridge = new ProviderDuplicationBridge(catalog, gate, new MinecraftProviderDuplicationPort(server),
                new MinecraftDimensionStoragePathResolver(server, catalog), observingCopier);
        try {
            bridge.registerReady(source.id(), identity(original), 1);

            AtomicReference<ProviderDuplicationBridge.BridgeOutcome> completion = new AtomicReference<>();
            AtomicInteger completionCount = new AtomicInteger();
            require(helper, bridge.beginDuplicate(source.id(), destination.id(), outcome -> { completion.set(outcome); completionCount.incrementAndGet(); })
                    == ProviderDuplicationBridge.BridgeAdmission.ADMITTED, "provider close admission");
            require(helper, gate.tryAcquireReadyRead(source.id()) == null, "source read must close at admission");
            helper.runAfterDelay(1, () -> awaitTerminal(helper, server, gate, source, destination, original, sourcePath, destinationPath,
                    completion, completionCount, markerRegion, 0));
        } catch (ClosedDimensionCopyException e) {
            helper.fail("provider bridge refused owned fixture: " + e);
        }
    }

    private static void awaitTerminal(GameTestHelper helper, MinecraftServer server, VerseOperationGate gate,
                                      OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination, ServerLevel original,
                                      Path sourcePath, Path destinationPath,
                                      AtomicReference<ProviderDuplicationBridge.BridgeOutcome> completion, AtomicInteger completionCount,
                                      AtomicReference<Path> markerRegion, int waited) {
        if (completion.get() == null) {
            if (waited >= 120) {
                helper.fail("provider close did not publish a terminal outcome; "
                        + terminalDiagnostics(server, gate, source, destination, completion, completionCount));
                return;
            }
            helper.runAfterDelay(1, () -> awaitTerminal(helper, server, gate, source, destination, original, sourcePath, destinationPath,
                    completion, completionCount, markerRegion, waited + 1));
            return;
        }
        require(helper, completion.get() == ProviderDuplicationBridge.BridgeOutcome.LOADED && completionCount.get() == 1,
                "one successful close/copy/restore/destination-load outcome; "
                        + terminalDiagnostics(server, gate, source, destination, completion, completionCount));
        ServerLevel restored = requireNonNull(helper, server.getLevel(source.key()), "restored source level");
        require(helper, restored != original, "restoration must produce a new source instance");
        require(helper, restored.getBlockState(MARKER).is(Blocks.REDSTONE_LAMP), "persisted source marker");
        VerseOperationGate.ReadyRead ready = gate.tryAcquireReadyRead(source.id());
        try { require(helper, ready != null && ready.identity().equals(identity(restored)), "restored identity/readiness"); }
        finally { if (ready != null) ready.close(); }
        ServerLevel loadedDestination = requireNonNull(helper, server.getLevel(destination.key()), "loaded destination level");
        require(helper, loadedDestination != original && loadedDestination != restored, "destination has a distinct loaded identity");
        require(helper, loadedDestination.dimension().equals(destination.key()), "destination registered with the owned key");
        require(helper, loadedDestination.getBlockState(MARKER).is(Blocks.REDSTONE_LAMP), "copied destination marker");
        VerseOperationGate.ReadyRead destinationReady = gate.tryAcquireReadyRead(destination.id());
        try { require(helper, destinationReady != null && destinationReady.identity().equals(identity(loadedDestination)), "destination identity/readiness"); }
        finally { if (destinationReady != null) destinationReady.close(); }
        loadedDestination.setBlock(new BlockPos(2, 64, 1), Blocks.GLOWSTONE.defaultBlockState(), 3);
        require(helper, !restored.getBlockState(new BlockPos(2, 64, 1)).is(Blocks.GLOWSTONE), "source/destination mutations are independent");
        restored.setBlock(new BlockPos(3, 64, 1), Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
        require(helper, !loadedDestination.getBlockState(new BlockPos(3, 64, 1)).is(Blocks.DIAMOND_BLOCK), "destination/source mutations are independent");
        require(helper, markerRegion.get() != null && Files.isRegularFile(destinationPath.resolve(markerRegion.get()), LinkOption.NOFOLLOW_LINKS),
                "observed copied marker region");
        require(helper, Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS), "source directory retained");
        helper.succeed();
    }

    private static void requireFresh(GameTestHelper helper, DynamicDimensionRegistry registry, MinecraftServer server, ResourceKey<Level> source,
                                     ResourceKey<Level> destination, Path sourcePath, Path destinationPath) {
        require(helper, !registry.anyDimensionExists(source.location()) && !registry.anyDimensionExists(destination.location()), "owned key already exists");
        require(helper, server.getLevel(source) == null && server.getLevel(destination) == null, "owned key already loaded");
        require(helper, !Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS) && !Files.exists(destinationPath, LinkOption.NOFOLLOW_LINKS),
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

    private static String terminalDiagnostics(MinecraftServer server, VerseOperationGate gate,
                                              OwnedDimensionDescriptor source, OwnedDimensionDescriptor destination,
                                              AtomicReference<ProviderDuplicationBridge.BridgeOutcome> completion,
                                              AtomicInteger completionCount) {
        VerseOperationGate.ReadyRead sourceRead = gate.tryAcquireReadyRead(source.id());
        VerseOperationGate.ReadyRead destinationRead = gate.tryAcquireReadyRead(destination.id());
        try {
            ServerLevel sourceLevel = server.getLevel(source.key());
            ServerLevel destinationLevel = server.getLevel(destination.key());
            return "outcome=" + completion.get() + ", completionCount=" + completionCount.get()
                    + ", sourceLevel=" + (sourceLevel == null ? "null" : identity(sourceLevel))
                    + ", sourceGate=" + (sourceRead == null ? "unavailable" : sourceRead.identity() + "@" + sourceRead.generation())
                    + ", destinationLevel=" + (destinationLevel == null ? "null" : identity(destinationLevel))
                    + ", destinationGate=" + (destinationRead == null ? "unavailable" : destinationRead.identity() + "@" + destinationRead.generation());
        } finally {
            if (sourceRead != null) sourceRead.close();
            if (destinationRead != null) destinationRead.close();
        }
    }

    private static <T> T requireNonNull(GameTestHelper helper, T value, String message) {
        if (value == null) helper.fail(message);
        return value;
    }

    private static void require(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }

    private static void copyRequire(boolean condition, String message) throws ClosedDimensionCopyException {
        if (!condition) throw new ClosedDimensionCopyException(message);
    }
}
