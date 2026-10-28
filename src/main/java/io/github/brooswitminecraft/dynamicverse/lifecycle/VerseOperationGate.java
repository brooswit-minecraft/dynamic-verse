package io.github.brooswitminecraft.dynamicverse.lifecycle;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.SourceWriteReloadFence;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionCopyRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared lifecycle/read gate. An operation keeps both identities unavailable
 * from admission through source restoration; the copier lease is subordinate.
 */
public final class VerseOperationGate {
    private final Map<String, Entry> entries = new HashMap<>();

    public synchronized void publishReady(String key, String identity, long generation) {
        Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
        if (entry.operating || entry.reads != 0 || entry.quarantined) throw new IllegalStateException("Cannot replace active or quarantined ready state: " + key);
        entry.identity = Objects.requireNonNull(identity);
        entry.generation = generation;
        entry.ready = true;
        entry.quarantined = false;
    }

    public synchronized Operation beginOperation(String source, String destination) {
        Entry sourceEntry = entries.get(source);
        Entry destinationEntry = entries.computeIfAbsent(destination, ignored -> new Entry());
        if (sourceEntry == null || !sourceEntry.ready || sourceEntry.operating || sourceEntry.reads != 0
                || destinationEntry.ready || destinationEntry.operating || destinationEntry.reads != 0 || destinationEntry.quarantined) {
            throw new IllegalStateException("Source/destination is not exclusively available for lifecycle operation");
        }
        sourceEntry.ready = false;
        sourceEntry.operating = true;
        destinationEntry.operating = true;
        return new Operation(source, destination, sourceEntry.identity, sourceEntry.generation);
    }

    /**
     * Exclusively stages a brand-new owned destination.  Unlike a duplicate
     * operation there is no source to restore, so a failed post-provider
     * create deliberately remains quarantined rather than becoming reusable.
     */
    public synchronized CreateReservation beginCreate(String destination) {
        Entry entry = entries.computeIfAbsent(destination, ignored -> new Entry());
        if (entry.ready || entry.operating || entry.reads != 0 || entry.quarantined) {
            throw new IllegalStateException("Destination is not exclusively available for create: " + destination);
        }
        entry.operating = true;
        return new CreateReservation(destination);
    }

    public synchronized ReadyRead tryAcquireReadyRead(String key) {
        Entry entry = entries.get(key);
        if (entry == null || !entry.ready || entry.operating || entry.quarantined) return null;
        entry.reads++;
        return new ReadyRead(key, entry.identity, entry.generation);
    }

    /** Non-leasing observation for truthful command listing. */
    public synchronized Readiness readiness(String key) {
        Entry entry = entries.get(key);
        if (entry == null) return Readiness.pending();
        if (entry.ready && !entry.operating && !entry.quarantined) return Readiness.ready(entry.identity, entry.generation);
        return entry.quarantined ? Readiness.quarantined() : Readiness.pending();
    }

    public record Readiness(State state, String identity, long generation) {
        public static Readiness ready(String identity, long generation) { return new Readiness(State.READY, identity, generation); }
        public static Readiness pending() { return new Readiness(State.PENDING, null, 0); }
        public static Readiness quarantined() { return new Readiness(State.QUARANTINED, null, 0); }
        public enum State { READY, PENDING, QUARANTINED }
    }

    public final class ReadyRead implements AutoCloseable {
        private final String key;
        private final String identity;
        private final long generation;
        private boolean closed;
        private ReadyRead(String key, String identity, long generation) { this.key = key; this.identity = identity; this.generation = generation; }
        public String identity() { return identity; }
        public long generation() { return generation; }
        @Override public void close() { synchronized (VerseOperationGate.this) { if (!closed) { entries.get(key).reads--; closed = true; } } }
    }

    /** Reservation held from create admission through exact observed publication. */
    public final class CreateReservation implements AutoCloseable {
        private final String destination;
        private boolean published;
        private boolean closed;

        private CreateReservation(String destination) { this.destination = destination; }

        public synchronized void publishReady(String identity, long generation) {
            if (closed || published) throw new IllegalStateException("Create reservation is no longer publishable");
            synchronized (VerseOperationGate.this) {
                Entry entry = entries.get(destination);
                if (!entry.operating || entry.reads != 0 || entry.quarantined) {
                    throw new IllegalStateException("Create destination lost exclusivity");
                }
                entry.identity = Objects.requireNonNull(identity);
                entry.generation = generation;
                entry.ready = true;
                entry.operating = false;
                published = true;
            }
        }

        @Override public void close() { synchronized (VerseOperationGate.this) {
            if (closed) return;
            Entry entry = entries.get(destination);
            if (!published) {
                entry.ready = false;
                entry.quarantined = true;
            }
            entry.operating = false;
            closed = true;
        }}
    }

    public final class Operation implements AutoCloseable {
        private final String source;
        private final String destination;
        private String sourceIdentity;
        private long sourceGeneration;
        private Long terminalGeneration;
        private boolean restored;
        private boolean admissionRefused;
        private boolean destinationPublished;
        private CopyReservation reservation;
        private boolean closed;
        private Operation(String source, String destination, String sourceIdentity, long sourceGeneration) {
            this.source = source; this.destination = destination; this.sourceIdentity = sourceIdentity; this.sourceGeneration = sourceGeneration;
        }
        public synchronized boolean claimTerminal(long generation) {
            if (terminalGeneration != null) return false;
            terminalGeneration = generation;
            return true;
        }
        public SourceWriteReloadFence copierFence() {
            return (key, generation) -> { synchronized (VerseOperationGate.this) {
                if (closed || terminalGeneration == null || !source.equals(key) || terminalGeneration != generation) {
                    throw new IllegalStateException("Copy is outside the admitted operation/generation");
                }
                return () -> { }; // sublease ends, while Operation remains exclusive through restoration.
            }};
        }
        public synchronized void restored(String identity, long generation) { sourceIdentity = Objects.requireNonNull(identity); sourceGeneration = generation; restored = true; }
        public synchronized void revokeRestoration() {
            restored = false;
            if (destinationPublished) synchronized (VerseOperationGate.this) {
                Entry destinationEntry = entries.get(destination);
                destinationEntry.ready = false;
                destinationEntry.quarantined = true;
                destinationPublished = false;
            }
        }
        /** Binds one copied directory to this exact admitted close generation. */
        public synchronized CopyReservation reserveCopied(String expectedSource, String expectedDestination, long generation,
                                                          ClosedDimensionCopyRequest request) {
            Objects.requireNonNull(request);
            if (closed || reservation != null || terminalGeneration == null || terminalGeneration != generation
                    || !source.equals(expectedSource) || !destination.equals(expectedDestination)
                    || !request.sourceKey().equals(source) || request.generation() != generation
                    || request.sourceRelative().equals(request.destinationRelative())) {
                throw new IllegalStateException("Copied directory is not bound to the admitted operation/generation");
            }
            reservation = new CopyReservation(source, destination, generation, request);
            return reservation;
        }
        /** The bridge alone may publish a destination after an unconsumed reservation and verified identities. */
        public synchronized void publishDestination(CopyReservation claimed, String identity, long generation) {
            if (closed || claimed == null || claimed != reservation || claimed.consumed || !restored || generation != terminalGeneration) {
                throw new IllegalStateException("Destination publication is not authorized by the copied operation");
            }
            synchronized (VerseOperationGate.this) {
                Entry destinationEntry = entries.get(destination);
                destinationEntry.identity = Objects.requireNonNull(identity);
                destinationEntry.generation = generation;
                destinationEntry.ready = true;
                destinationEntry.quarantined = false;
                destinationPublished = true;
                claimed.consumed = true;
            }
        }
        /** Admission was refused before any provider close ticket existed. */
        public synchronized void admissionRefused() { restored = true; admissionRefused = true; }
        @Override public void close() { synchronized (VerseOperationGate.this) {
            if (closed) return;
            Entry sourceEntry = entries.get(source);
            Entry destinationEntry = entries.get(destination);
            sourceEntry.operating = false;
            destinationEntry.operating = false;
            if (restored) {
                sourceEntry.identity = sourceIdentity;
                sourceEntry.generation = sourceGeneration;
                sourceEntry.ready = true;
                if (!destinationPublished && !admissionRefused) destinationEntry.quarantined = true;
            } else {
                sourceEntry.quarantined = true;
                destinationEntry.quarantined = true;
            }
            closed = true;
        }}
    }

    public static final class CopyReservation {
        private final String sourceKey;
        private final String destinationKey;
        private final long generation;
        private final ClosedDimensionCopyRequest request;
        private boolean consumed;
        private CopyReservation(String sourceKey, String destinationKey, long generation, ClosedDimensionCopyRequest request) {
            this.sourceKey = sourceKey; this.destinationKey = destinationKey; this.generation = generation; this.request = request;
        }
        public String sourceKey() { return sourceKey; }
        public String destinationKey() { return destinationKey; }
        public long generation() { return generation; }
        public ClosedDimensionCopyRequest request() { return request; }
    }

    private static final class Entry { String identity; long generation; int reads; boolean ready; boolean operating; boolean quarantined; }
}
