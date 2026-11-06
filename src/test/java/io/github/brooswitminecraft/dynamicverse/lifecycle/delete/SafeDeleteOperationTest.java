package io.github.brooswitminecraft.dynamicverse.lifecycle.delete;

import static org.junit.jupiter.api.Assertions.*;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeDeleteOperationTest {
    @TempDir Path root;
    @Test void exactCloseDeletesOnceAfterIntentWhileReadsStayExcluded() {
        Fixture f=new Fixture(); assertEquals(SafeDeleteOperation.Admission.ADMITTED,f.operation.start());
        assertNull(f.gate.tryAcquireReadyRead(f.target.key())); assertTrue(f.intent); assertEquals(0,f.removals);
        f.deliver(f.success()); f.deliver(f.success());
        assertEquals(1,f.removals); assertFalse(f.intent); assertEquals(List.of(SafeDeleteOperation.Outcome.DELETED),f.outcomes);
        assertNull(f.gate.tryAcquireReadyRead(f.target.key()));
    }
    @Test void inlineCompletionWaitsUntilProviderAdmissionReturns() {
        Fixture f=new Fixture(); f.inline=true;
        assertEquals(SafeDeleteOperation.Admission.ADMITTED,f.operation.start());
        assertEquals(1,f.removals); assertEquals(List.of(SafeDeleteOperation.Outcome.DELETED),f.outcomes);
    }
    @Test void inlineTerminalFollowedByProviderThrowOrRefusalNeverDeletes() {
        for (boolean throwsAfter:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.inline=true; f.providerAccepted=false; f.providerThrows=throwsAfter;
            assertEquals(SafeDeleteOperation.Admission.ADMITTED,f.operation.start());
            assertEquals(0,f.removals); assertTrue(f.intent); f.assertQuarantined();
        }
    }
    @Test void badWrongOrFailedReceiptCannotAuthorizeRemoval() {
        for (ClosedDimensionReceipt receipt:List.of(
                new ClosedDimensionReceipt("dynamicverse:other",7,true,ClosedDimensionStatus.SAVED_AND_CLOSED,false),
                new ClosedDimensionReceipt("dynamicverse:owned",7,false,ClosedDimensionStatus.SAVED_AND_CLOSED,false),
                new ClosedDimensionReceipt("dynamicverse:owned",7,true,ClosedDimensionStatus.FAILED_CLOSE,false),
                new ClosedDimensionReceipt("dynamicverse:owned",7,true,ClosedDimensionStatus.SAVED_AND_CLOSED,true),
                new ClosedDimensionReceipt("dynamicverse:owned",-1,true,ClosedDimensionStatus.SAVED_AND_CLOSED,false))) {
            Fixture f=new Fixture(); f.operation.start(); f.deliver(receipt); assertEquals(0,f.removals); f.assertQuarantined();
        }
    }
    @Test void occupiedOrChangedLevelRefusesBeforeIntentAndProvider() {
        for (boolean changed:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.players=!changed; f.current=!changed;
            assertEquals(changed?SafeDeleteOperation.Admission.NOT_CURRENT:SafeDeleteOperation.Admission.PLAYERS_PRESENT,f.operation.start());
            assertFalse(f.intent); assertEquals(0,f.providerCalls); assertNotNull(f.gate.tryAcquireReadyRead(f.target.key()));
        }
    }
    @Test void provenProviderRefusalRestoresReadinessAndCancelsOnlyItsIntent() {
        Fixture f=new Fixture(); f.providerAccepted=false;
        assertEquals(SafeDeleteOperation.Admission.REFUSED,f.operation.start());
        assertFalse(f.intent); assertEquals(0,f.removals); assertNotNull(f.gate.tryAcquireReadyRead(f.target.key()));
        assertTrue(f.outcomes.isEmpty());
    }
    @Test void uncertainProviderFailureKeepsIntentAndQuarantine() {
        Fixture f=new Fixture(); f.providerThrows=true;
        assertEquals(SafeDeleteOperation.Admission.ADMITTED,f.operation.start());
        assertEquals(0,f.removals); assertTrue(f.intent); f.assertQuarantined();
    }
    @Test void timeoutAndRetirementIgnoreLateTerminalsWithoutRemoval() {
        for (boolean abort:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.operation.start();
            if (abort) f.operation.abort(); else for(int i=0;i<200;i++) f.operation.tick();
            f.deliver(f.success()); assertEquals(0,f.removals); assertTrue(f.intent); f.assertQuarantined();
        }
    }
    @Test void uncertainIntentWriteCannotReachProvider() {
        Fixture f=new Fixture(); f.beginWriteFails=true;
        assertEquals(SafeDeleteOperation.Admission.ADMITTED,f.operation.start());
        assertEquals(0,f.providerCalls); assertEquals(0,f.removals); assertTrue(f.intent); f.assertQuarantined();
    }
    @Test void failedFinalJournalWriteNeverPublishesDeletionOrAllowsReplay() {
        Fixture f=new Fixture(); f.finishWriteFails=true; f.operation.start(); f.deliver(f.success());
        assertEquals(1,f.removals); assertTrue(f.intent); f.assertQuarantined();
    }
    @Test void partialRemovalFailureKeepsIntentAndNeverRetries() {
        Fixture f=new Fixture(); f.removalFails=true; f.operation.start(); f.deliver(f.success()); f.deliver(f.success());
        assertEquals(1,f.removals); assertTrue(f.intent); f.assertQuarantined();
    }
    @Test void successfulReceiptWithoutActualAbsenceNeverDeletes() {
        Fixture f=new Fixture(); f.operation.start(); f.callback.accept(f.success());
        assertEquals(0,f.removals); f.assertQuarantined();
    }
    @Test void heldReadAndDeleteReservationsExcludeEachOther() {
        VerseOperationGate gate=new VerseOperationGate(); gate.publishReady("dynamicverse:owned","live",1);
        var read=gate.tryAcquireReadyRead("dynamicverse:owned");
        assertThrows(IllegalStateException.class,()->gate.beginDelete("dynamicverse:owned")); read.close();
        var deletion=gate.beginDelete("dynamicverse:owned"); assertNull(gate.tryAcquireReadyRead("dynamicverse:owned"));
        deletion.close(); assertEquals(VerseOperationGate.Readiness.State.QUARANTINED,gate.readiness("dynamicverse:owned").state());
    }
    private final class Fixture implements SafeDeleteOperation.Port,SafeDeleteOperation.Journal,SafeDeleteOperation.Removal {
        final SafeDeleteOperation.Target target=new SafeDeleteOperation.Target("dynamicverse:owned","live",1,root,
                Path.of("dimensions/dynamicverse/owned"),UUID.randomUUID());
        final VerseOperationGate gate=new VerseOperationGate();
        final List<SafeDeleteOperation.Outcome> outcomes=new ArrayList<>();
        final SafeDeleteOperation operation;
        Consumer<ClosedDimensionReceipt> callback;
        boolean current=true,players,absent,providerAccepted=true,providerThrows,inline,intent,beginWriteFails,finishWriteFails,removalFails;
        int providerCalls,removals;
        Fixture() { gate.publishReady(target.key(),"live",1); operation=new SafeDeleteOperation(target,this,this,this,gate.beginDelete(target.key()),outcomes::add); }
        public boolean matchesCurrent(SafeDeleteOperation.Target t) { return current; }
        public boolean hasPlayers(SafeDeleteOperation.Target t) { return players; }
        public boolean beginClose(SafeDeleteOperation.Target t,Consumer<ClosedDimensionReceipt> callback) {
            assertTrue(intent); providerCalls++; this.callback=callback;
            if(inline) deliver(success());
            if(providerThrows) throw new IllegalStateException("uncertain close");
            return providerAccepted;
        }
        public boolean isClosedAndAbsent(SafeDeleteOperation.Target t) { return absent; }
        public void begin(SafeDeleteOperation.Target t) { intent=true; if(beginWriteFails) throw new IllegalStateException("uncertain write"); }
        public void cancelKnownNoEffect(SafeDeleteOperation.Target t) { intent=false; }
        public void deleted(SafeDeleteOperation.Target t) { if(finishWriteFails) throw new IllegalStateException("uncertain commit"); intent=false; }
        public void validate(SafeDeleteOperation.Target t) { }
        public void delete(SafeDeleteOperation.Target t,ClosedDimensionReceipt receipt,SourceWriteReloadFence fence) throws Exception {
            assertTrue(intent); try(var retained=fence.retain(t.key(),receipt.generation())) { assertNotNull(retained); removals++; }
            if(removalFails) throw new IllegalStateException("partial removal");
        }
        ClosedDimensionReceipt success() { return new ClosedDimensionReceipt(target.key(),7,true,ClosedDimensionStatus.SAVED_AND_CLOSED,false); }
        void deliver(ClosedDimensionReceipt receipt) { absent=true; callback.accept(receipt); }
        void assertQuarantined() {
            assertEquals(VerseOperationGate.Readiness.State.QUARANTINED,gate.readiness(target.key()).state());
            assertEquals(List.of(SafeDeleteOperation.Outcome.QUARANTINED),outcomes);
        }
    }
}
