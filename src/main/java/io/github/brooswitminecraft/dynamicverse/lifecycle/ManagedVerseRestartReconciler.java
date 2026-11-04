package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Objects;
import java.util.function.Consumer;

/** Production-used conservative restart step: it changes no provider state and never invents success. */
final class ManagedVerseRestartReconciler {
    private ManagedVerseRestartReconciler() { }

    static ManagedVerseStateStore.State interruptNonterminal(ManagedVerseStateStore.State before,
                                                              Consumer<ManagedVerseStateStore.OperationIntent> excludeDestination) {
        Objects.requireNonNull(before); Objects.requireNonNull(excludeDestination);
        ManagedVerseStateStore.State after = before;
        for (ManagedVerseStateStore.OperationIntent intent : before.operations().values()) {
            if (intent.phase() == ManagedVerseStateStore.OperationPhase.PENDING) {
                excludeDestination.accept(intent);
                after = after.withOperation(intent.interrupted());
            }
            else if (intent.phase() == ManagedVerseStateStore.OperationPhase.FAILED) {
                // A later fresh service must retain the conservative exclusion
                // established by the first restart, rather than forgetting it.
                excludeDestination.accept(intent);
            }
        }
        return after;
    }
}
