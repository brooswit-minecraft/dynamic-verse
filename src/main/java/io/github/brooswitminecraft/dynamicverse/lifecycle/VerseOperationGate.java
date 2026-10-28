package io.github.brooswitminecraft.dynamicverse.lifecycle;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.SourceWriteReloadFence;

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

    public synchronized ReadyRead tryAcquireReadyRead(String key) {
        Entry entry = entries.get(key);
        if (entry == null || !entry.ready || entry.operating || entry.quarantined) return null;
        entry.reads++;
        return new ReadyRead(key, entry.identity, entry.generation);
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

    public final class Operation implements AutoCloseable {
        private final String source;
        private final String destination;
        private String sourceIdentity;
        private long sourceGeneration;
        private Long terminalGeneration;
        private boolean restored;
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
        /** Admission was refused before any provider close ticket existed. */
        public synchronized void admissionRefused() { restored = true; }
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
            } else {
                sourceEntry.quarantined = true;
                destinationEntry.quarantined = true;
            }
            closed = true;
        }}
    }

    private static final class Entry { String identity; long generation; int reads; boolean ready; boolean operating; boolean quarantined; }
}
