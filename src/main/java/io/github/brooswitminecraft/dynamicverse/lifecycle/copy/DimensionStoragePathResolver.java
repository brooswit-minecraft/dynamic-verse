package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

/**
 * Production bridge for the provider-owned storage mapping. It must obtain
 * both exact dimension paths via LevelStorageAccess#getDimensionPath, derive
 * their common storage root, and reject a mapping it cannot prove confined.
 */
@FunctionalInterface
public interface DimensionStoragePathResolver {
    ClosedDimensionCopyRequest resolve(ClosedDimensionDuplicationIntent intent) throws ClosedDimensionCopyException;
}
