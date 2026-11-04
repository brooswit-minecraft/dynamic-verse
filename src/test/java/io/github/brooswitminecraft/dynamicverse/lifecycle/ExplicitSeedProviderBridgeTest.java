package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.ExplicitSeedDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.PlayerRemover;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseCompletion;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.Test;

/** Exercises the real lazy marker boundary; production never uses reflection. */
class ExplicitSeedProviderBridgeTest {
    @Test void oldRegistryIsUnavailableWithoutCallingAnySeedOrLegacyOperation() {
        LegacyRegistry legacy = new LegacyRegistry();
        assertTrue(ExplicitSeedProviderBridge.discover(legacy).isEmpty());
        assertEquals(0, legacy.calls);
    }

    @Test void markerRegistryExposesOnlyExplicitSeedOperations() {
        SeededRegistry registry = new SeededRegistry();
        Optional<ServerVerseRuntime.CreateGateway.ExplicitSeedOperations> operations = ExplicitSeedProviderBridge.discover(registry);
        assertTrue(operations.isPresent());
        assertEquals(0, registry.calls);
    }

    @Test void markerOperationsDispatchTheExactSeededArgumentsWithoutLegacyFallback() {
        SeededRegistry registry = new SeededRegistry();
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dynamicverse", "exact-dispatch");
        DynamicDimensionProperties properties = new DynamicDimensionProperties() {
            @Override public int priority() { return 7; }
            @Override public org.joml.Vector3f baseGravity() { return new org.joml.Vector3f(1, 2, 3); }
            @Override public double basePressure() { return 4; }
            @Override public float universalDrag() { return 5; }
            @Override public org.joml.Vector3f magneticNorth() { return new org.joml.Vector3f(6, 7, 8); }
        };
        ServerVerseRuntime.CreateGateway.ExplicitSeedOperations operations = ExplicitSeedProviderBridge.discover(registry).orElseThrow();
        operations.create(id, null, null, properties, 123L);
        operations.reload(id, null, null, properties, 456L);
        assertSame(id, registry.createId); assertSame(properties, registry.createProperties); assertEquals(123L, registry.createSeed);
        assertSame(id, registry.reloadId); assertSame(properties, registry.reloadProperties); assertEquals(456L, registry.reloadSeed);
        assertEquals(2, registry.calls);
    }

    @Test void linkageFailureAtTheLazyBridgeIsUnavailableInsteadOfEscaping() {
        LegacyRegistry legacy = new LegacyRegistry();
        Optional<ServerVerseRuntime.CreateGateway.ExplicitSeedOperations> operations =
                ServerVerseRuntime.CreateGateway.discoverExplicit(legacy, ignored -> { throw new NoClassDefFoundError("ExplicitSeedDimensionRegistry"); });
        assertTrue(operations.isEmpty());
        assertEquals(0, legacy.calls);
    }

    @Test void isolatedProductionFactoryCatchesDeniedMarkerWhileConstructingItsAnonymousGateway() throws Exception {
        String packagePrefix = "io.github.brooswitminecraft.dynamicverse.lifecycle.";
        String runtime = packagePrefix + "ServerVerseRuntime";
        ClassLoader parent = ExplicitSeedProviderBridge.class.getClassLoader();
        try (URLClassLoader isolated = new URLClassLoader(new URL[0], parent) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals("dev.galacticraft.dynamicdimensions.api.ExplicitSeedDimensionRegistry")) {
                    throw new ClassNotFoundException(name);
                }
                if (name.startsWith(packagePrefix + "ServerVerseRuntime") || name.equals(packagePrefix + "ExplicitSeedProviderBridge")) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        String resource = name.replace('.', '/') + ".class";
                        try (InputStream bytes = parent.getResourceAsStream(resource)) {
                            if (bytes == null) throw new ClassNotFoundException(name);
                            byte[] value = bytes.readAllBytes();
                            loaded = defineClass(name, value, 0, value.length);
                        } catch (java.io.IOException failure) { throw new ClassNotFoundException(name, failure); }
                    }
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            // This loads the real outer type, nested CreateGateway, bridge, and the anonymous production gateway.
            // The child loader denies only the optional marker, so the production LinkageError catch is exercised.
            Class<?> nested = Class.forName(runtime + "$CreateGateway", true, isolated);
            Method factory = nested.getDeclaredMethod("minecraft", net.minecraft.server.MinecraftServer.class, DynamicDimensionRegistry.class);
            factory.setAccessible(true);
            LegacyRegistry legacy = new LegacyRegistry();
            Object gateway;
            try {
                gateway = factory.invoke(null, null, legacy);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("production discovery escaped denied optional marker", failure.getCause());
            }
            Method supported = nested.getMethod("supportsExplicitSeeds");
            assertFalse((Boolean) supported.invoke(gateway));
            assertEquals(runtime + "$CreateGateway$1", gateway.getClass().getName());
            assertEquals(isolated, gateway.getClass().getClassLoader());
            assertEquals(0, legacy.calls);
        }
    }

    private static class LegacyRegistry implements DynamicDimensionRegistry {
        int calls;
        @Override public boolean dynamicDimensionExists(ResourceKey<Level> key) { calls++; return false; }
        @Override public boolean anyDimensionExists(ResourceLocation id) { calls++; return false; }
        @Override public boolean canDeleteDimension(ResourceKey<Level> key) { calls++; return false; }
        @Override public boolean canCreateDimension(ResourceLocation id) { calls++; return false; }
        @Override public ServerLevel createDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type) { calls++; return null; }
        @Override public ServerLevel createDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type, DynamicDimensionProperties properties) { calls++; return null; }
        @Override public ServerLevel createDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type, long seed) { calls++; return null; }
        @Override public ServerLevel createDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type, DynamicDimensionProperties properties, long seed) { calls++; return null; }
        @Override public ServerLevel loadDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type) { calls++; return null; }
        @Override public ServerLevel loadDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type, DynamicDimensionProperties properties) { calls++; return null; }
        @Override public ServerLevel loadDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type, long seed) { calls++; return null; }
        @Override public ServerLevel loadDynamicDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type, DynamicDimensionProperties properties, long seed) { calls++; return null; }
        @Override public void setDimensionProperties(ResourceKey<Level> key, DynamicDimensionProperties properties) { calls++; }
        @Override public DynamicDimensionProperties getDimensionProperties(ResourceKey<Level> key) { calls++; return null; }
        @Override public void clearDimensionProperties(ResourceKey<Level> key) { calls++; }
        @Override public boolean deleteDynamicDimension(ResourceLocation id, PlayerRemover remover) { calls++; return false; }
        @Override public boolean unloadDynamicDimension(ResourceLocation id, PlayerRemover remover) { calls++; return false; }
        @Override public boolean beginQuiesceAndClose(ResourceLocation id, PlayerRemover remover, QuiesceAndCloseCompletion completion) { calls++; return false; }
    }

    private static final class SeededRegistry extends LegacyRegistry implements ExplicitSeedDimensionRegistry {
        private ResourceLocation createId;
        private DynamicDimensionProperties createProperties;
        private long createSeed;
        private ResourceLocation reloadId;
        private DynamicDimensionProperties reloadProperties;
        private long reloadSeed;
        @Override public ServerLevel createExplicitSeedDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type,
                                                                 DynamicDimensionProperties properties, long seed) {
            calls++; createId = id; createProperties = properties; createSeed = seed; return null;
        }
        @Override public ServerLevel loadExplicitSeedDimension(ResourceLocation id, ChunkGenerator generator, DimensionType type,
                                                               DynamicDimensionProperties properties, long seed) {
            calls++; reloadId = id; reloadProperties = properties; reloadSeed = seed; return null;
        }
    }
}
