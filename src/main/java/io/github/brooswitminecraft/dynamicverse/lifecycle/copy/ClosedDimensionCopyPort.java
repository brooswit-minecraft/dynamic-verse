package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

/**
 * The production copy boundary used by {@link ClosedDimensionDuplicationCoordinator}.
 * The sole implementation is {@link ClosedDimensionCopier}; the interface keeps
 * coordinator tests on the same admission and outcome path without a world runtime.
 */
@FunctionalInterface
public interface ClosedDimensionCopyPort {
    void copy(ClosedDimensionCopyRequest request, ClosedDimensionReceipt receipt,
              SourceWriteReloadFence fence) throws ClosedDimensionCopyException;
}
