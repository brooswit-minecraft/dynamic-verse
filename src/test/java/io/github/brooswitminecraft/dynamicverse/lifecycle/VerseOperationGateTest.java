package io.github.brooswitminecraft.dynamicverse.lifecycle;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.SourceWriteReloadFence;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerseOperationGateTest {
    @Test
    void operationExcludesReadyReadsThroughSubleaseUntilRestoration() {
        VerseOperationGate gate = new VerseOperationGate();
        gate.publishReady("source", "source-instance", 12);
        VerseOperationGate.Operation operation = gate.beginOperation("source", "destination");

        assertNull(gate.tryAcquireReadyRead("source"));
        assertTrue(operation.claimTerminal(31));
        SourceWriteReloadFence.Lease sublease = operation.copierFence().retain("source", 31);
        assertNotNull(sublease);
        sublease.close();
        assertNull(gate.tryAcquireReadyRead("source"), "copier sublease close must not open a pre-restoration window");

        operation.restored();
        operation.close();
        VerseOperationGate.ReadyRead read = gate.tryAcquireReadyRead("source");
        assertNotNull(read);
        assertEquals("source-instance", read.identity());
        read.close();
    }

    @Test
    void failedOperationQuarantinesSourceAndDestinationAndRejectsTerminalReplay() {
        VerseOperationGate gate = new VerseOperationGate();
        gate.publishReady("source", "source-instance", 12);
        VerseOperationGate.Operation operation = gate.beginOperation("source", "destination");

        assertTrue(operation.claimTerminal(31));
        assertFalse(operation.claimTerminal(31));
        operation.close();
        assertNull(gate.tryAcquireReadyRead("source"));
        assertNull(gate.tryAcquireReadyRead("destination"));
    }

    @Test
    void readyReadFacadeIsNonblockingAndUsesTheSameGate() {
        VerseOperationGate gate = new VerseOperationGate();
        gate.publishReady("source", "source-instance", 12);
        GatedVerseAccessService access = new GatedVerseAccessService(gate);
        AtomicReference<VerseReservationSnapshot> terminal = new AtomicReference<>();

        assertEquals(VerseReservationPhase.PENDING, access.beginAcquireReadyRead("source", terminal::set).phase());
        assertNull(terminal.get());
        access.tick();
        ReadyVerseLease lease = terminal.get().lease().orElseThrow();
        assertEquals("source-instance", lease.levelIdentity());
        lease.close();
    }
}
