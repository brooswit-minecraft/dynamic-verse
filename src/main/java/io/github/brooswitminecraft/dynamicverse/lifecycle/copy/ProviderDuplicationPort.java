package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.function.Consumer;

/** Provider operations used by the production bridge; controlled ports exercise the identical coordinator path in unit tests. */
public interface ProviderDuplicationPort {
    boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, Consumer<ProviderQuiesceCloseTerminal> terminal);
    RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long restorationGeneration);
    LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation);
    boolean isCurrent(OwnedDimensionDescriptor descriptor, Object instance);
    boolean isAbsent(OwnedDimensionDescriptor descriptor);
    boolean hasCopiedDestination(ClosedDimensionCopyRequest request);

    record RestoredSource(Object instance, String instanceIdentity, long generation) { }
    record LoadedDestination(Object instance, String instanceIdentity, long generation) { }
}
