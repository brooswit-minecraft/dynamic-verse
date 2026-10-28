package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * One-shot production coordinator between a provider close terminal and the
 * confined copier. It has no provider, filesystem, load, or publication
 * fallback: unsupported mapping, fence refusal, and copy failure all leave the
 * destination unavailable for this coordinator instance.
 */
public final class ClosedDimensionDuplicationCoordinator {
    private final DimensionStoragePathResolver paths;
    private final ClosedDimensionCopyPort copier;
    private final SourceWriteReloadFence fence;
    private final Set<ReceiptId> consumedTerminals = new HashSet<>();
    private final Set<String> claimedDestinations = new HashSet<>();
    private final Set<String> unavailableDestinations = new HashSet<>();

    public ClosedDimensionDuplicationCoordinator(DimensionStoragePathResolver paths,
                                                 ClosedDimensionCopyPort copier,
                                                 SourceWriteReloadFence fence) {
        this.paths = Objects.requireNonNull(paths);
        this.copier = Objects.requireNonNull(copier);
        this.fence = Objects.requireNonNull(fence);
    }

    public synchronized ClosedDimensionDuplicationResult duplicate(
            ClosedDimensionDuplicationIntent intent, ProviderQuiesceCloseTerminal terminal) {
        return duplicate(intent, terminal, fence);
    }

    /** The operation bridge supplies its gate-owned sublease for real provider work. */
    public synchronized ClosedDimensionDuplicationResult duplicate(
            ClosedDimensionDuplicationIntent intent, ProviderQuiesceCloseTerminal terminal, SourceWriteReloadFence operationFence) {
        Objects.requireNonNull(intent);
        Objects.requireNonNull(operationFence);
        if (terminal == null || !terminal.receipt().isExactSuccess(intent.sourceKey(), intent.generation())) {
            return rejected("Terminal is absent, mismatched, or not SAVED_AND_CLOSED without failure");
        }

        ReceiptId receiptId = new ReceiptId(intent.sourceKey(), intent.generation());
        if (!consumedTerminals.add(receiptId)) return rejected("Close terminal was already consumed");
        if (!claimedDestinations.add(intent.destinationKey())) return rejected("Destination was already claimed");

        ClosedDimensionCopyRequest request;
        try {
            request = Objects.requireNonNull(paths.resolve(intent), "Storage mapping resolver returned null");
            if (!request.sourceKey().equals(intent.sourceKey()) || request.generation() != intent.generation()) {
                throw new ClosedDimensionCopyException("Storage mapping identity does not match admitted source");
            }
        } catch (ClosedDimensionCopyException | RuntimeException e) {
            return unavailable(intent.destinationKey(), "Storage mapping/fence boundary refused copy: " + e.getMessage(), e);
        }

        try {
            copier.copy(request, terminal.receipt(), operationFence);
            return new ClosedDimensionDuplicationResult(ClosedDimensionDuplicationResult.Outcome.COPIED,
                    "Exact close terminal copied; separate ordered load wiring is still required");
        } catch (ClosedDimensionCopyException | RuntimeException e) {
            return unavailable(intent.destinationKey(), "Copy failed; retained destination must not load or publish: " + e.getMessage(), e);
        }
    }

    public synchronized boolean isUnavailable(String destinationKey) {
        return unavailableDestinations.contains(destinationKey);
    }

    private ClosedDimensionDuplicationResult unavailable(String destinationKey, String detail, Throwable cause) {
        unavailableDestinations.add(destinationKey);
        return new ClosedDimensionDuplicationResult(ClosedDimensionDuplicationResult.Outcome.UNAVAILABLE, detail, cause);
    }

    private static ClosedDimensionDuplicationResult rejected(String detail) {
        return new ClosedDimensionDuplicationResult(ClosedDimensionDuplicationResult.Outcome.REJECTED, detail);
    }

    private record ReceiptId(String sourceKey, long generation) { }
}
