package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.ArrayDeque;
import java.util.Queue;

/** Ready-read facade that shares the lifecycle operation gate. */
public final class GatedVerseAccessService implements VerseAccessService {
    private final VerseOperationGate gate;
    private final Queue<Request> pending = new ArrayDeque<>();
    public GatedVerseAccessService(VerseOperationGate gate) { this.gate = gate; }
    @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) {
        VerseReservationId id = VerseReservationId.newId();
        pending.add(new Request(id, verseName, sink));
        return VerseReservationSnapshot.pending(id, verseName);
    }
    /** Called from the cooperating server-tick lifecycle owner; never blocks. */
    public void tick() {
        while (!pending.isEmpty()) {
            Request request = pending.remove();
            VerseOperationGate.ReadyRead read = gate.tryAcquireReadyRead(request.verseName());
            VerseReservationSnapshot result = read == null
                    ? VerseReservationSnapshot.failed(request.id(), request.verseName(), VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING)
                    : VerseReservationSnapshot.acquired(request.id(), new ReadyVerseLease(request.verseName(), read.identity(), read.generation(), request.id(), read::close));
            request.sink().complete(result);
        }
    }

    private record Request(VerseReservationId id, String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) { }
}
