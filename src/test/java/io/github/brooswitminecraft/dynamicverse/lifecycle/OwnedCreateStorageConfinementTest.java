package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedCreateStorageConfinementTest {
    @TempDir Path sandbox;

    @Test void acceptsFreshConfinedDynamicVerseDestination() throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("world"));
        assertTrue(OwnedCreateStorageConfinement.isFreshConfined(root, key("fresh")));
    }

    @Test void refusesLinkedDimensionsAncestorBeforeProviderCreate() throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("world"));
        Path outside = Files.createDirectory(sandbox.resolve("outside"));
        Files.createSymbolicLink(root.resolve("dimensions"), outside);
        assertFalse(OwnedCreateStorageConfinement.isFreshConfined(root, key("linked")));
    }

    @Test void refusesLinkedNamespaceAncestorBeforeProviderCreate() throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("world"));
        Path outside = Files.createDirectory(sandbox.resolve("outside"));
        Files.createDirectories(root.resolve("dimensions"));
        Files.createSymbolicLink(root.resolve("dimensions").resolve("dynamicverse"), outside);
        assertFalse(OwnedCreateStorageConfinement.isFreshConfined(root, key("linked")));
    }

    @Test void acceptsOnlyAnExistingConfinedOwnedDirectoryForRecovery() throws Exception {
        Path root = Files.createDirectory(sandbox.resolve("world"));
        assertFalse(OwnedCreateStorageConfinement.isExistingConfined(root, key("retained")));
        Files.createDirectories(root.resolve("dimensions/dynamicverse/retained"));
        assertTrue(OwnedCreateStorageConfinement.isExistingConfined(root, key("retained")));
    }

    private static ResourceKey<Level> key(String path) {
        return ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", path));
    }
}
