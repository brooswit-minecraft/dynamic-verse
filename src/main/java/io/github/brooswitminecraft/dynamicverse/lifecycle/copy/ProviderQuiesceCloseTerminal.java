package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.Objects;

/**
 * Dependency-free image of the pinned provider's terminal completion. The
 * provider bridge must map {@code QuiesceAndCloseResult.key()},
 * {@code generation()}, {@code initiallyLoaded()}, {@code status()}, and the
 * presence of either {@code failureStage()} or {@code cause()} exactly once.
 */
public record ProviderQuiesceCloseTerminal(
        String sourceKey,
        long generation,
        boolean initiallyLoaded,
        ClosedDimensionStatus status,
        boolean failurePresent) {
    public ProviderQuiesceCloseTerminal {
        Objects.requireNonNull(sourceKey);
        Objects.requireNonNull(status);
    }

    ClosedDimensionReceipt receipt() {
        return new ClosedDimensionReceipt(sourceKey, generation, initiallyLoaded, status, failurePresent);
    }
}
