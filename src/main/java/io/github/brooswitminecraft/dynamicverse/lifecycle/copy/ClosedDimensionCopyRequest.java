package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Provider-resolved storage mapping. Relative paths must be obtained from the
 * pinned provider's LevelStorageAccess mapping, never from a command string.
 */
public record ClosedDimensionCopyRequest(
        Path storageRoot,
        Path sourceRelative,
        Path destinationRelative,
        String sourceKey,
        long generation) {
    public ClosedDimensionCopyRequest {
        Objects.requireNonNull(storageRoot);
        Objects.requireNonNull(sourceRelative);
        Objects.requireNonNull(destinationRelative);
        Objects.requireNonNull(sourceKey);
    }
}
