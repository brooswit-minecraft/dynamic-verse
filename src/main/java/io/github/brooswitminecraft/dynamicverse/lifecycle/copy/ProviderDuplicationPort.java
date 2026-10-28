package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.function.Consumer;

/** Provider operations used by the production bridge; controlled ports exercise the identical coordinator path in unit tests. */
public interface ProviderDuplicationPort {
    boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, Consumer<ProviderQuiesceCloseTerminal> terminal);
    boolean loadOriginalSource(OwnedDimensionDescriptor source);
}
