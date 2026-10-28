package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.ArrayDeque;
import java.util.Queue;

/** Ready-read facade that shares the lifecycle operation gate. */
public final class GatedVerseAccessService implements VerseAccessService {
    private final VerseOperationGate gate;
    private final Queue<Request> pending = new ArrayDeque<>();
    private boolean retired;
    public GatedVerseAccessService(VerseOperationGate gate) { this.gate = gate; }
    @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) {
        VerseReservationId id = VerseReservationId.newId();
        if (retired) {
            VerseReservationSnapshot failed = VerseReservationSnapshot.failed(id, verseName, VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING);
            sink.complete(failed);
            return failed;
        }
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
                    : VerseReservationSnapshot.acquired(request.id(), new ReadyVerseLease(request.verseName(), read.identity(), read.generation(), request.id(), read::close, this));
            request.sink().complete(result);
        }
    }
    /** Terminal shutdown cancellation; no request is left awaiting a tick that will never occur. */
    public void retire() {
        if (retired) return;
        retired = true;
        while (!pending.isEmpty()) {
            Request request = pending.remove();
            request.sink().complete(VerseReservationSnapshot.failed(request.id(), request.verseName(), VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING));
        }
    }

    private record Request(VerseReservationId id, String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) { }
}
