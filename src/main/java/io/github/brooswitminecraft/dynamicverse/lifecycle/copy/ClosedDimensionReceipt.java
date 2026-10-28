package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.Objects;

/** Immutable, one-terminal receipt required before a closed source may be copied. */
public record ClosedDimensionReceipt(
        String sourceKey,
        long generation,
        boolean initiallyLoaded,
        ClosedDimensionStatus status,
        boolean failurePresent) {
    public ClosedDimensionReceipt {
        Objects.requireNonNull(sourceKey);
        Objects.requireNonNull(status);
    }

    public boolean isExactSuccess(String expectedKey, long expectedGeneration) {
        return initiallyLoaded && !failurePresent && status == ClosedDimensionStatus.SAVED_AND_CLOSED
                && sourceKey.equals(expectedKey) && generation == expectedGeneration;
    }
}
