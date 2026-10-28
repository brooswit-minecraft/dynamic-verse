package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.Objects;

/** Identity fixed when a duplication request is admitted, before any close completes. */
public record ClosedDimensionDuplicationIntent(String sourceKey, String destinationKey, long generation) {
    public ClosedDimensionDuplicationIntent {
        Objects.requireNonNull(sourceKey);
        Objects.requireNonNull(destinationKey);
        if (sourceKey.isBlank() || destinationKey.isBlank()) throw new IllegalArgumentException("Dimension keys must not be blank");
        if (sourceKey.equals(destinationKey)) throw new IllegalArgumentException("Source and destination keys must differ");
    }
}
