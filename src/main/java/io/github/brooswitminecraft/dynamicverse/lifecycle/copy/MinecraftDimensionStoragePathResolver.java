package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.Objects;

/** Supported NeoForge 21.1.148 mapping: public ROOT path plus public DimensionType storage mapping. */
public final class MinecraftDimensionStoragePathResolver implements DimensionStoragePathResolver {
    private final MinecraftServer server;
    private final OwnedDimensionCatalog catalog;

    public MinecraftDimensionStoragePathResolver(MinecraftServer server, OwnedDimensionCatalog catalog) {
        this.server = Objects.requireNonNull(server);
        this.catalog = Objects.requireNonNull(catalog);
    }

    @Override
    public ClosedDimensionCopyRequest resolve(ClosedDimensionDuplicationIntent intent) throws ClosedDimensionCopyException {
        OwnedDimensionDescriptor source = catalog.require(intent.sourceKey());
        OwnedDimensionDescriptor destination = catalog.require(intent.destinationKey());
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path sourcePath = DimensionType.getStorageFolder(source.key(), root).toAbsolutePath().normalize();
        Path destinationPath = DimensionType.getStorageFolder(destination.key(), root).toAbsolutePath().normalize();
        if (sourcePath.equals(root) || destinationPath.equals(root) || !sourcePath.startsWith(root) || !destinationPath.startsWith(root)) {
            throw new ClosedDimensionCopyException("Unsupported root/escaped dimension storage mapping");
        }
        Path sourceRelative = root.relativize(sourcePath);
        Path destinationRelative = root.relativize(destinationPath);
        if (sourceRelative.getNameCount() < 3 || destinationRelative.getNameCount() < 3
                || !sourceRelative.getName(0).toString().equals("dimensions")
                || !destinationRelative.getName(0).toString().equals("dimensions")) {
            throw new ClosedDimensionCopyException("Only owned custom dimensions mapping under dimensions/ are supported");
        }
        return new ClosedDimensionCopyRequest(root, sourceRelative, destinationRelative, intent.sourceKey(), intent.generation());
    }
}
