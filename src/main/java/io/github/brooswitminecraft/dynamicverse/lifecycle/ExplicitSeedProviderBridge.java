package io.github.brooswitminecraft.dynamicverse.lifecycle;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.ExplicitSeedDimensionRegistry;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;

/**
 * Deliberately lazy adapter for the provider's opt-in explicit-seed contract.
 * The outer Runtime never mentions the marker, so a legacy provider can fail
 * closed while still serving omitted-seed operations.
 */
final class ExplicitSeedProviderBridge {
    private ExplicitSeedProviderBridge() { }

    static Optional<ServerVerseRuntime.CreateGateway.ExplicitSeedOperations> discover(DynamicDimensionRegistry registry) {
        if (!(registry instanceof ExplicitSeedDimensionRegistry seeded)) return Optional.empty();
        return Optional.of(new ServerVerseRuntime.CreateGateway.ExplicitSeedOperations() {
            @Override public ServerLevel create(ResourceLocation id, ChunkGenerator generator, DimensionType type,
                                                DynamicDimensionProperties properties, long seed) {
                return seeded.createExplicitSeedDimension(id, generator, type, properties, seed);
            }

            @Override public ServerLevel reload(ResourceLocation id, ChunkGenerator generator, DimensionType type,
                                                DynamicDimensionProperties properties, long seed) {
                return seeded.loadExplicitSeedDimension(id, generator, type, properties, seed);
            }
        });
    }
}
