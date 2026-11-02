package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.function.Consumer;
import java.util.OptionalLong;

/** Provider operations used by the production bridge; controlled ports exercise the identical coordinator path in unit tests. */
public interface ProviderDuplicationPort {
    default boolean supportsSeededReload() { return false; }
    default OptionalLong effectiveSeed(Object instance) { return OptionalLong.empty(); }
    boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, Consumer<ProviderQuiesceCloseTerminal> terminal);
    RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long restorationGeneration);
    LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation);
    /** Seeded reloads are explicit: legacy reload is never an implicit fallback. */
    default RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long generation, OptionalLong seed) {
        if (seed.isPresent()) throw new UnsupportedOperationException("seeded source reload unavailable");
        return loadOriginalSource(source, generation);
    }
    default LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation, OptionalLong seed) {
        if (seed.isPresent()) throw new UnsupportedOperationException("seeded destination reload unavailable");
        return loadCopiedDestination(destination, generation);
    }
    boolean isCurrent(OwnedDimensionDescriptor descriptor, Object instance);
    boolean isAbsent(OwnedDimensionDescriptor descriptor);
    boolean hasCopiedDestination(ClosedDimensionCopyRequest request);
    void awaitCurrent(OwnedDimensionDescriptor descriptor, Object instance, Consumer<Boolean> completion);

    record RestoredSource(Object instance, String instanceIdentity, long generation) { }
    record LoadedDestination(Object instance, String instanceIdentity, long generation) { }
}
