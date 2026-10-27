package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerseAccessServiceContractTest {
    @Test
    void acquisitionIsPendingUntilTickThenHoldsAndReleasesLease() {
        DeterministicVerseAccessService service = new DeterministicVerseAccessService();
        service.publishReady("alpha", "level-alpha", 1);
        AtomicReference<VerseReservationSnapshot> completion = new AtomicReference<>();

        VerseReservationSnapshot admission = service.beginAcquireReadyRead("alpha", completion::set);

        assertEquals(VerseReservationPhase.PENDING, admission.phase());
        assertEquals(null, completion.get());
        service.tick();

        ReadyVerseLease lease = completion.get().lease().orElseThrow();
        assertEquals(VerseReservationPhase.ACQUIRED, completion.get().phase());
        assertEquals("level-alpha", lease.levelIdentity());
        assertEquals(1, service.activeReads("alpha"));
        lease.close();
        assertEquals(0, service.activeReads("alpha"));
    }

    @Test
    void pendingLifecycleFailsAtUnderLeaseRevalidation() {
        DeterministicVerseAccessService service = new DeterministicVerseAccessService();
        service.publishReady("alpha", "level-alpha", 1);
        AtomicReference<VerseReservationSnapshot> completion = new AtomicReference<>();

        service.beginAcquireReadyRead("alpha", completion::set);
        service.markLifecyclePending("alpha");
        service.tick();

        assertEquals(VerseReservationPhase.FAILED, completion.get().phase());
        assertEquals(VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING, completion.get().error().orElseThrow());
    }

    @Test
    void replacementAfterAdmissionNeverReturnsStaleLevel() {
        DeterministicVerseAccessService service = new DeterministicVerseAccessService();
        service.publishReady("alpha", "level-alpha-v1", 1);
        AtomicReference<VerseReservationSnapshot> completion = new AtomicReference<>();

        service.beginAcquireReadyRead("alpha", completion::set);
        service.publishReady("alpha", "level-alpha-v2", 2);
        service.tick();

        assertEquals(VerseReservationPhase.FAILED, completion.get().phase());
        assertEquals(VerseAccessError.ERR_VERSE_DESTINATION_CHANGED, completion.get().error().orElseThrow());
    }

    @Test
    void deletionAfterAdmissionFailsWithoutLease() {
        DeterministicVerseAccessService service = new DeterministicVerseAccessService();
        service.publishReady("alpha", "level-alpha", 1);
        AtomicReference<VerseReservationSnapshot> completion = new AtomicReference<>();

        service.beginAcquireReadyRead("alpha", completion::set);
        service.delete("alpha");
        service.tick();

        assertEquals(VerseReservationPhase.FAILED, completion.get().phase());
        assertEquals(VerseAccessError.ERR_VERSE_NOT_FOUND, completion.get().error().orElseThrow());
        assertTrue(completion.get().lease().isEmpty());
    }
}
