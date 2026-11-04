package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Objects;

/** A Dynamic Verse-owned, non-vanilla descriptor retained for normal restoration. */
public record OwnedDimensionDescriptor(ResourceKey<Level> key, ChunkGenerator generator,
                                       DimensionType type, DynamicDimensionProperties properties) {
    public OwnedDimensionDescriptor {
        Objects.requireNonNull(key);
        Objects.requireNonNull(generator);
        Objects.requireNonNull(type);
        Objects.requireNonNull(properties);
        if (key == Level.OVERWORLD || key == Level.NETHER || key == Level.END || key.location().getNamespace().equals("minecraft")) {
            throw new IllegalArgumentException("Vanilla/root dimensions are never owned duplication descriptors");
        }
    }

    public String id() { return key.location().toString(); }
}
