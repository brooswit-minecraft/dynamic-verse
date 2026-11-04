package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test void explicitRequestedAndObservedEffectiveSeedRoundTripWithoutSharedSeedSubstitution() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("seed-data"));
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(data.resolve("managed.properties"));
        ManagedVerseStateStore.ReadyVerse seeded = new ManagedVerseStateStore.ReadyVerse("dynamicverse:seeded",
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(17L), 17L, 4L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        ManagedVerseStateStore.ReadyVerse legacy = ready("dynamicverse:legacy", VerseLifecycleCommandService.CreateType.SUPERFLAT, 29L, 5L);
        store.write(new ManagedVerseStateStore.State(Map.of(seeded.id(), seeded, legacy.id(), legacy), Map.of()));
        ManagedVerseStateStore.State reread = store.read();
        assertEquals(java.util.OptionalLong.of(17L), reread.ready().get(seeded.id()).requestedSeed());
        assertEquals(17L, reread.ready().get(seeded.id()).effectiveSeed());
        assertTrue(reread.ready().get(legacy.id()).requestedSeed().isEmpty(), "legacy entries remain shared-save-seed provenance");
    }

    @Test void interruptedSeededOperationRoundTripsRequestedSeedWithoutLegacyInference() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("seed-operation-data"));
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(data.resolve("managed.properties"));
        ManagedVerseStateStore.ReadyVerse destination = new ManagedVerseStateStore.ReadyVerse("dynamicverse:seeded-copy",
                VerseLifecycleCommandService.CreateType.SUPERFLAT, java.util.OptionalLong.of(31L), 31L, 0L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        UUID id = UUID.randomUUID();
        ManagedVerseStateStore.OperationIntent intent = new ManagedVerseStateStore.OperationIntent(id,
                ManagedVerseStateStore.OperationKind.DUPLICATE, "dynamicverse:source", destination.id(), true, destination,
                ManagedVerseStateStore.OperationPhase.PENDING, null);
        store.write(new ManagedVerseStateStore.State(Map.of(), Map.of(id, intent)));
        ManagedVerseStateStore.OperationIntent reread = store.read().operations().get(id);
        assertEquals(java.util.OptionalLong.of(31L), reread.destinationDescriptor().requestedSeed());
        assertEquals(31L, reread.destinationDescriptor().effectiveSeed());
        assertEquals(ManagedVerseStateStore.OperationPhase.FAILED,
                ManagedVerseRestartReconciler.interruptNonterminal(store.read(), ignored -> { }).operations().get(id).phase());
    }

    @Test void seededCreateIntentRoundTripsExactRequestedAndEffectiveSeed() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("seeded-create-intent-data"));
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(data.resolve("managed.properties"));
        long seed = 991337L;
        ManagedVerseStateStore.ReadyVerse destination = new ManagedVerseStateStore.ReadyVerse("dynamicverse:seeded-created",
                VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.of(seed), seed, 0L,
                "OVERWORLD_RECONSTRUCTED", "DEFAULT_DYNAMIC_PROPERTIES");
        UUID id = UUID.randomUUID();
        ManagedVerseStateStore.OperationIntent intent = new ManagedVerseStateStore.OperationIntent(id,
                ManagedVerseStateStore.OperationKind.CREATE, null, destination.id(), false, destination,
                ManagedVerseStateStore.OperationPhase.PENDING, null);

        store.write(new ManagedVerseStateStore.State(Map.of(), Map.of(id, intent)));
        ManagedVerseStateStore.OperationIntent reread = new ManagedVerseStateFileStore(data.resolve("managed.properties"))
                .read().operations().get(id);

        assertEquals(id, reread.id());
        assertEquals(ManagedVerseStateStore.OperationKind.CREATE, reread.kind());
        assertEquals(null, reread.source());
        assertEquals(destination.id(), reread.destination());
        assertFalse(reread.priorSourceLoaded());
        assertEquals(ManagedVerseStateStore.OperationPhase.PENDING, reread.phase());
        assertEquals(java.util.OptionalLong.of(seed), reread.destinationDescriptor().requestedSeed());
        assertEquals(seed, reread.destinationDescriptor().effectiveSeed());
    }

    @Test void literalLegacyTenFieldOperationRecordRemainsUnrequested() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("legacy-operation-data"));
        Path file = data.resolve("managed.properties");
        UUID id = UUID.randomUUID();
        Files.writeString(file, "operation." + id + "=DUPLICATE|dynamicverse:source|dynamicverse:destination|true|PENDING|dynamicverse:destination|NORMAL|41|0|OVERWORLD_RECONSTRUCTED\n");
        ManagedVerseStateStore.OperationIntent intent = new ManagedVerseStateFileStore(file).read().operations().get(id);
        assertTrue(intent.destinationDescriptor().requestedSeed().isEmpty());
        assertEquals(41L, intent.destinationDescriptor().effectiveSeed());
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

    @Test void staleLegacyTemporaryStateDoesNotBlockOrReplaceCommittedState() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path file = data.resolve("managed.properties");
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(file);
        ManagedVerseStateStore.ReadyVerse first = ready("dynamicverse:first", VerseLifecycleCommandService.CreateType.NORMAL, 9L, 1L);
        store.write(new ManagedVerseStateStore.State(Map.of(first.id(), first), Map.of()));
        Path stale = data.resolve("managed.properties.tmp");
        String staleBytes = "interrupted replacement";
        Files.writeString(stale, staleBytes);
        ManagedVerseStateStore.ReadyVerse second = ready("dynamicverse:second", VerseLifecycleCommandService.CreateType.SUPERFLAT, 9L, 2L);

        store.write(new ManagedVerseStateStore.State(Map.of(second.id(), second), Map.of()));
        assertEquals(second, store.read().ready().get(second.id()));
        assertFalse(store.read().ready().containsKey(first.id()));
        assertEquals(staleBytes, Files.readString(stale), "legacy residue stays uncommitted evidence");
    }

    @Test void pendingIntentReconcilesPastResidueAndSurvivesFreshFileStoreRead() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path file = data.resolve("managed.properties");
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(file);
        ManagedVerseStateStore.ReadyVerse source = ready("dynamicverse:source", VerseLifecycleCommandService.CreateType.NORMAL, 4L, 3L);
        ManagedVerseStateStore.ReadyVerse destination = ready("dynamicverse:destination", VerseLifecycleCommandService.CreateType.SUPERFLAT, 4L, 0L);
        UUID id = UUID.randomUUID();
        ManagedVerseStateStore.OperationIntent pending = new ManagedVerseStateStore.OperationIntent(id,
                ManagedVerseStateStore.OperationKind.DUPLICATE, source.id(), destination.id(), true, destination,
                ManagedVerseStateStore.OperationPhase.PENDING, null);
        store.write(new ManagedVerseStateStore.State(Map.of(source.id(), source), Map.of(id, pending)));
        Path stale = data.resolve("managed.properties.tmp");
        String staleBytes = "uncommitted replacement";
        Files.writeString(stale, staleBytes);

        java.util.ArrayList<String> excluded = new java.util.ArrayList<>();
        ManagedVerseStateStore.State reconciled = ManagedVerseRestartReconciler.interruptNonterminal(store.read(),
                intent -> excluded.add(intent.destination()));
        store.write(reconciled);
        ManagedVerseStateStore.State fresh = new ManagedVerseStateFileStore(file).read();

        assertEquals(java.util.List.of(destination.id()), excluded);
        assertEquals(source, fresh.ready().get(source.id()), "committed READY source survives; travel still needs runtime replay verification");
        ManagedVerseStateStore.OperationIntent interrupted = fresh.operations().get(id);
        assertEquals(ManagedVerseStateStore.OperationPhase.FAILED, interrupted.phase());
        assertEquals("ERR_OPERATION_INTERRUPTED", interrupted.error());
        assertFalse(fresh.ready().containsKey(destination.id()));
        assertEquals(staleBytes, Files.readString(stale), "stale content is never adopted or modified");
    }

    @Test void multipleRetainedStagingResiduesCannotBlockFreshOwnedWrites() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path file = data.resolve("managed.properties");
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(file);
        ManagedVerseStateStore.ReadyVerse first = ready("dynamicverse:first", VerseLifecycleCommandService.CreateType.NORMAL, 9L, 1L);
        store.write(new ManagedVerseStateStore.State(Map.of(first.id(), first), Map.of()));
        Path legacy = data.resolve("managed.properties.tmp");
        Path priorUnique = data.resolve("managed.properties.staging-prior.tmp");
        Files.writeString(legacy, "legacy evidence");
        Files.writeString(priorUnique, "prior unique evidence");
        ManagedVerseStateStore.ReadyVerse second = ready("dynamicverse:second", VerseLifecycleCommandService.CreateType.NORMAL, 9L, 2L);
        ManagedVerseStateStore.ReadyVerse third = ready("dynamicverse:third", VerseLifecycleCommandService.CreateType.NORMAL, 9L, 3L);

        store.write(new ManagedVerseStateStore.State(Map.of(second.id(), second), Map.of()));
        store.write(new ManagedVerseStateStore.State(Map.of(third.id(), third), Map.of()));
        assertEquals(third, store.read().ready().get(third.id()));
        assertEquals("legacy evidence", Files.readString(legacy));
        assertEquals("prior unique evidence", Files.readString(priorUnique));
    }

    @Test void symlinkedStagingCollisionIsRefusedWithoutTruncatingItsTarget() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path file = data.resolve("managed.properties");
        Path target = data.resolve("unrelated.txt");
        Files.writeString(target, "unrelated target bytes");
        Path collision = data.resolve("managed.properties.staging-collision.tmp");
        Files.createSymbolicLink(collision, target);
        AtomicInteger names = new AtomicInteger();
        ManagedVerseStateFileStore store = new ManagedVerseStateFileStore(file,
                () -> names.getAndIncrement() == 0 ? "collision" : "owned");
        ManagedVerseStateStore.ReadyVerse ready = ready("dynamicverse:owned", VerseLifecycleCommandService.CreateType.NORMAL, 12L, 1L);

        store.write(new ManagedVerseStateStore.State(Map.of(ready.id(), ready), Map.of()));
        assertEquals(2, names.get(), "CREATE_NEW retries once after the occupied candidate");
        assertTrue(Files.isSymbolicLink(collision));
        assertEquals("unrelated target bytes", Files.readString(target), "collision target was never opened or truncated");
        assertEquals(ready, store.read().ready().get(ready.id()));
    }

    @Test void malformedCommittedStateNeverAdoptsTempAndAtomicReplacementFailurePreservesIt() throws Exception {
        Path data = Files.createDirectory(temporary.resolve("data"));
        Path malformed = data.resolve("malformed.properties");
        Path plausibleTemp = data.resolve("malformed.properties.tmp");
        Files.writeString(malformed, "ready.dynamicverse:bad=not|a|descriptor");
        Files.writeString(plausibleTemp, "ready.dynamicverse:plausible=NORMAL|7|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES");
        ManagedVerseStateFileStore malformedStore = new ManagedVerseStateFileStore(malformed);
        assertThrows(IllegalStateException.class, malformedStore::read);
        assertEquals("ready.dynamicverse:bad=not|a|descriptor", Files.readString(malformed));
        assertEquals("ready.dynamicverse:plausible=NORMAL|7|1|OVERWORLD_RECONSTRUCTED|DEFAULT_DYNAMIC_PROPERTIES", Files.readString(plausibleTemp));

        Path committed = data.resolve("committed.properties");
        ManagedVerseStateFileStore good = new ManagedVerseStateFileStore(committed);
        ManagedVerseStateStore.ReadyVerse first = ready("dynamicverse:first", VerseLifecycleCommandService.CreateType.NORMAL, 11L, 1L);
        ManagedVerseStateStore.ReadyVerse second = ready("dynamicverse:second", VerseLifecycleCommandService.CreateType.NORMAL, 11L, 2L);
        good.write(new ManagedVerseStateStore.State(Map.of(first.id(), first), Map.of()));
        ManagedVerseStateFileStore failingMove = new ManagedVerseStateFileStore(committed,
                (staging, target) -> { throw new IOException("injected atomic replace failure"); });
        assertThrows(IllegalStateException.class, () -> failingMove.write(new ManagedVerseStateStore.State(Map.of(second.id(), second), Map.of())));
        assertEquals(first, good.read().ready().get(first.id()), "failed replacement cannot publish proposed state");
        assertFalse(good.read().ready().containsKey(second.id()));
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
