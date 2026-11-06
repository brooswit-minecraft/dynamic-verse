package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Application-owned durable intent.  This intentionally stores only the
 * supported create provenance, never ServerLevel, registry-holder, or object
 * identities.  A successful file replacement is an ordering boundary, not a
 * claim that arbitrary storage has a crash-atomic commit protocol.
 */
public interface ManagedVerseStateStore {
    State read();
    void write(State state);

    record State(Map<String, ReadyVerse> ready, Map<UUID, OperationIntent> operations) {
        public State {
            ready = Map.copyOf(ready);
            operations = Map.copyOf(operations);
        }
        public static State empty() { return new State(Map.of(), Map.of()); }
        public State withReady(ReadyVerse value) {
            java.util.HashMap<String, ReadyVerse> copy = new java.util.HashMap<>(ready);
            copy.put(value.id(), value);
            return new State(copy, operations);
        }
        public State withoutReady(String key) {
            java.util.HashMap<String, ReadyVerse> copy = new java.util.HashMap<>(ready);
            copy.remove(key);
            return new State(copy, operations);
        }
        public State withOperation(OperationIntent value) {
            java.util.HashMap<UUID, OperationIntent> copy = new java.util.HashMap<>(operations);
            copy.put(value.id(), value);
            return new State(ready, copy);
        }
        public State withoutOperation(UUID id) {
            java.util.HashMap<UUID, OperationIntent> copy = new java.util.HashMap<>(operations);
            copy.remove(id);
            return new State(ready, copy);
        }
    }

    /** Descriptor reconstruction is deliberately limited to our approved create forms. */
    record ReadyVerse(String id, VerseLifecycleCommandService.CreateType generator, OptionalLong requestedSeed, long effectiveSeed,
                      long generation, String typeProvenance, String propertiesProvenance) {
        public ReadyVerse {
            Objects.requireNonNull(id); Objects.requireNonNull(generator);
            Objects.requireNonNull(requestedSeed);
            if (!"OVERWORLD_RECONSTRUCTED".equals(typeProvenance) || !"DEFAULT_DYNAMIC_PROPERTIES".equals(propertiesProvenance)) {
                throw new IllegalArgumentException("Unsupported persisted descriptor provenance");
            }
        }
        /** Legacy shared-save-seed entries did not have a requested per-verse seed. */
        public ReadyVerse(String id, VerseLifecycleCommandService.CreateType generator, long effectiveSeed,
                          long generation, String typeProvenance, String propertiesProvenance) {
            this(id, generator, OptionalLong.empty(), effectiveSeed, generation, typeProvenance, propertiesProvenance);
        }
    }

    enum OperationKind { CREATE, DUPLICATE, DELETE }
    enum OperationPhase { PENDING, FAILED }

    /** Nonterminal intent is never interpreted as success after a restart. */
    record OperationIntent(UUID id, OperationKind kind, String source, String destination,
                           boolean priorSourceLoaded, ReadyVerse destinationDescriptor,
                           OperationPhase phase, String error) {
        public OperationIntent {
            Objects.requireNonNull(id); Objects.requireNonNull(kind); Objects.requireNonNull(destination);
            Objects.requireNonNull(destinationDescriptor); Objects.requireNonNull(phase);
            if (phase == OperationPhase.FAILED && !"ERR_OPERATION_INTERRUPTED".equals(error)) {
                throw new IllegalArgumentException("Recovered failures use ERR_OPERATION_INTERRUPTED");
            }
            if (phase == OperationPhase.PENDING && error != null) throw new IllegalArgumentException("Pending operation has no terminal error");
        }
        public OperationIntent interrupted() {
            return new OperationIntent(id, kind, source, destination, priorSourceLoaded, destinationDescriptor,
                    OperationPhase.FAILED, "ERR_OPERATION_INTERRUPTED");
        }
    }
}
