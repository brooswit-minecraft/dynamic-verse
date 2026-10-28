package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.UUID;

/** Production terminal boundary: durable READY replacement precedes command success. */
final class ManagedVerseDurablePublication {
    private ManagedVerseDurablePublication() { }

    static boolean publishDuplicate(ManagedVerseStateStore store, VerseOperationGate gate, UUID operationId,
                                    ManagedVerseStateStore.ReadyVerse ready) {
        try {
            store.write(store.read().withReady(ready).withoutOperation(operationId));
            return true;
        } catch (RuntimeException failed) {
            gate.quarantine(ready.id());
            return false;
        }
    }
}
