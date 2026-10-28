package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedVerseStateFileStoreTest {
    @TempDir Path temporary;

    @Test void supportedReadyDescriptorAndOperationIntentRoundTripWithoutRuntimeIdentity() throws Exception {
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(temporary.resolve("data").resolve("managed.properties"));
        Files.createDirectory(temporary.resolve("data"));
        ManagedVerseStateStore.ReadyVerse ready = ready("dynamicverse:normal", VerseLifecycleCommandService.CreateType.NORMAL, 41L, 7L);
        ManagedVerseStateStore.ReadyVerse pendingDestination = ready("dynamicverse:destination", VerseLifecycleCommandService.CreateType.SUPERFLAT, 41L, 0L);
        UUID operationId = UUID.randomUUID();
        ManagedVerseStateStore.OperationIntent intent = new ManagedVerseStateStore.OperationIntent(operationId,
                ManagedVerseStateStore.OperationKind.DUPLICATE, "dynamicverse:normal", "dynamicverse:destination", true,
                pendingDestination, ManagedVerseStateStore.OperationPhase.PENDING, null);

        store.write(new ManagedVerseStateStore.State(Map.of(ready.id(), ready), Map.of(operationId, intent)));
        ManagedVerseStateStore.State recovered = store.read();

        assertEquals(ready, recovered.ready().get(ready.id()));
        assertEquals(intent, recovered.operations().get(operationId));
        String bytes = Files.readString(temporary.resolve("data/managed.properties"));
        assertFalse(bytes.contains("@"), "ephemeral ServerLevel identity is never persisted");
        assertTrue(bytes.contains("OVERWORLD_RECONSTRUCTED"));
    }

    @Test void restartConservativelyFailsEveryPendingOperationAndExcludesItsDestination() {
        UUID id = UUID.randomUUID();
        ManagedVerseStateStore.ReadyVerse destination = ready("dynamicverse:interrupted", VerseLifecycleCommandService.CreateType.NORMAL, 4L, 0L);
        ManagedVerseStateStore.OperationIntent pending = new ManagedVerseStateStore.OperationIntent(id,
                ManagedVerseStateStore.OperationKind.CREATE, null, destination.id(), false, destination,
                ManagedVerseStateStore.OperationPhase.PENDING, null);
        java.util.ArrayList<String> excluded = new java.util.ArrayList<>();

        ManagedVerseStateStore.State after = ManagedVerseRestartReconciler.interruptNonterminal(
                new ManagedVerseStateStore.State(Map.of(), Map.of(id, pending)), intent -> excluded.add(intent.destination()));

        assertEquals(java.util.List.of("dynamicverse:interrupted"), excluded);
        ManagedVerseStateStore.OperationIntent failed = after.operations().get(id);
        assertEquals(ManagedVerseStateStore.OperationPhase.FAILED, failed.phase());
        assertEquals("ERR_OPERATION_INTERRUPTED", failed.error());
        assertTrue(after.ready().isEmpty(), "interrupted destinations never become READY during recovery");
    }

    @Test void retainedTemporaryStateFileBlocksReplacementButPreservesTheLastReadableState() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path file = data.resolve("managed.properties");
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(file);
        ManagedVerseStateStore.ReadyVerse first = ready("dynamicverse:first", VerseLifecycleCommandService.CreateType.NORMAL, 9L, 1L);
        store.write(new ManagedVerseStateStore.State(Map.of(first.id(), first), Map.of()));
        Files.writeString(data.resolve("managed.properties.tmp"), "interrupted replacement");

        assertThrows(IllegalStateException.class, () -> store.write(ManagedVerseStateStore.State.empty()));
        assertEquals(first, store.read().ready().get(first.id()));
        assertTrue(Files.exists(data.resolve("managed.properties.tmp")), "interrupted evidence is retained, never deleted automatically");
    }

    @Test void duplicatePublicationRemovesIntentOnlyWithDurableReadyAndQuarantinesOnStoreFailure() {
        UUID id = UUID.randomUUID();
        ManagedVerseStateStore.ReadyVerse destination = ready("dynamicverse:copied", VerseLifecycleCommandService.CreateType.NORMAL, 12L, 3L);
        ManagedVerseStateStore.OperationIntent pending = new ManagedVerseStateStore.OperationIntent(id,
                ManagedVerseStateStore.OperationKind.DUPLICATE, "dynamicverse:source", destination.id(), true, destination,
                ManagedVerseStateStore.OperationPhase.PENDING, null);
        InMemoryManagedVerseStateStore good = new InMemoryManagedVerseStateStore();
        good.write(new ManagedVerseStateStore.State(Map.of(), Map.of(id, pending)));
        VerseOperationGate goodGate = new VerseOperationGate();
        assertTrue(ManagedVerseDurablePublication.publishDuplicate(good, goodGate, id, destination));
        assertEquals(destination, good.read().ready().get(destination.id()));
        assertFalse(good.read().operations().containsKey(id));

        ManagedVerseStateStore failing = new ManagedVerseStateStore() {
            @Override public State read() { return new State(Map.of(), Map.of(id, pending)); }
            @Override public void write(State ignored) { throw new IllegalStateException("injected store failure"); }
        };
        VerseOperationGate failedGate = new VerseOperationGate();
        assertFalse(ManagedVerseDurablePublication.publishDuplicate(failing, failedGate, id, destination));
        assertEquals(VerseOperationGate.Readiness.State.QUARANTINED, failedGate.readiness(destination.id()).state());
        assertTrue(failing.read().operations().containsKey(id), "failure retains conservative interrupted intent");
    }

    private static ManagedVerseStateStore.ReadyVerse ready(String id, VerseLifecycleCommandService.CreateType type, long seed, long generation) {
        return new ManagedVerseStateStore.ReadyVerse(id, type, seed, generation,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
    }
}
