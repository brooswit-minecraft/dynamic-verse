package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

/** Test-only event-loop double; it does not represent a Minecraft world. */
final class DeterministicVerseAccessService implements VerseAccessService {
    private final Map<String, Entry> ready = new HashMap<>();
    private final Queue<Request> pending = new ArrayDeque<>();
    private final Map<String, Integer> activeReads = new HashMap<>();

    void publishReady(String name, String levelIdentity, long generation) {
        ready.put(name, new Entry(levelIdentity, generation, false));
    }

    void markLifecyclePending(String name) {
        ready.computeIfPresent(name, (ignored, entry) -> new Entry(entry.levelIdentity(), entry.generation(), true));
    }

    void delete(String name) {
        ready.remove(name);
    }

    int activeReads(String name) {
        return activeReads.getOrDefault(name, 0);
    }

    @Override
    public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> sink) {
        VerseReservationId id = VerseReservationId.newId();
        Entry admitted = ready.get(verseName);
        pending.add(new Request(id, verseName, admitted == null ? -1 : admitted.generation(), sink));
        return VerseReservationSnapshot.pending(id, verseName);
    }

    void tick() {
        while (!pending.isEmpty()) {
            Request request = pending.remove();
            Entry current = ready.get(request.verseName());
            VerseReservationSnapshot terminal;
            if (current == null) {
                terminal = VerseReservationSnapshot.failed(request.id(), request.verseName(), VerseAccessError.ERR_VERSE_NOT_FOUND);
            } else if (current.lifecyclePending()) {
                terminal = VerseReservationSnapshot.failed(request.id(), request.verseName(), VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING);
            } else if (request.expectedGeneration() != current.generation()) {
                terminal = VerseReservationSnapshot.failed(request.id(), request.verseName(), VerseAccessError.ERR_VERSE_DESTINATION_CHANGED);
            } else {
                activeReads.merge(request.verseName(), 1, Integer::sum);
                ReadyVerseLease lease = new ReadyVerseLease(
                        request.verseName(), current.levelIdentity(), current.generation(), request.id(),
                        () -> activeReads.merge(request.verseName(), -1, Integer::sum));
                terminal = VerseReservationSnapshot.acquired(request.id(), lease);
            }
            request.sink().complete(terminal);
        }
    }

    private record Entry(String levelIdentity, long generation, boolean lifecyclePending) { }
    private record Request(VerseReservationId id, String verseName, long expectedGeneration,
                           VerseCompletionSink<VerseReservationSnapshot> sink) { }
}
