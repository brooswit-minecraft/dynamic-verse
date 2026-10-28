package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static ManagedVerseStateStore.ReadyVerse ready(String id, VerseLifecycleCommandService.CreateType type, long seed, long generation) {
        return new ManagedVerseStateStore.ReadyVerse(id, type, seed, generation,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
    }
}
