package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Objects;
import java.util.Optional;

/** Immutable command-facing state; only READY entries expose a live identity. */
public record ManagedVerseListing(String name, State state, Optional<String> levelIdentity, Optional<Long> generation) {
    public ManagedVerseListing {
        Objects.requireNonNull(name);
        Objects.requireNonNull(state);
        Objects.requireNonNull(levelIdentity);
        Objects.requireNonNull(generation);
        if (state == State.READY != (levelIdentity.isPresent() && generation.isPresent())) {
            throw new IllegalArgumentException("Only READY listings may expose a live level identity");
        }
    }
    public enum State { READY, PENDING, QUARANTINED }
}
