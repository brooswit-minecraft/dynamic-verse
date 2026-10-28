package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/** Existing-path validation before a destructive provider create.  It deliberately does not create directories. */
final class OwnedCreateStorageConfinement {
    private OwnedCreateStorageConfinement() { }

    static boolean isFreshConfined(Path worldRoot, ResourceKey<Level> key) {
        try {
            ResourceLocation id = key.location();
            if (!"dynamicverse".equals(id.getNamespace()) || id.getPath().isBlank()) return false;
            Path root = worldRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return false;
            Path destination = DimensionType.getStorageFolder(key, root).toAbsolutePath().normalize();
            Path expectedPrefix = root.resolve("dimensions").resolve(id.getNamespace());
            if (!destination.startsWith(expectedPrefix) || destination.equals(expectedPrefix)) return false;
            Path ancestor = root;
            for (Path component : root.relativize(destination)) {
                if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(ancestor)) return false;
                ancestor = ancestor.resolve(component);
            }
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(ancestor)) return false;
            return !Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /** Existing-only counterpart for recovery: it never creates a directory. */
    static boolean isExistingConfined(Path worldRoot, ResourceKey<Level> key) {
        try {
            ResourceLocation id = key.location();
            if (!"dynamicverse".equals(id.getNamespace()) || id.getPath().isBlank()) return false;
            Path root = worldRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return false;
            Path destination = DimensionType.getStorageFolder(key, root).toAbsolutePath().normalize();
            Path expectedPrefix = root.resolve("dimensions").resolve(id.getNamespace());
            if (!destination.startsWith(expectedPrefix) || destination.equals(expectedPrefix)
                    || !Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(destination)) return false;
            Path ancestor = root;
            for (Path component : root.relativize(destination)) {
                if (Files.isSymbolicLink(ancestor)) return false;
                ancestor = ancestor.resolve(component);
            }
            return !Files.isSymbolicLink(ancestor);
        } catch (RuntimeException unavailable) { return false; }
    }
}
