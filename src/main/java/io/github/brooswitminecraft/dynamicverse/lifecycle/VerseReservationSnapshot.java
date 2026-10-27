package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Objects;
import java.util.Optional;

/** Immutable observation of a nonblocking read-reservation operation. */
public record VerseReservationSnapshot(
        VerseReservationId id,
        String verseName,
        VerseReservationPhase phase,
        Optional<ReadyVerseLease> lease,
        Optional<VerseAccessError> error) {
    public VerseReservationSnapshot {
        Objects.requireNonNull(id);
        Objects.requireNonNull(verseName);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(lease);
        Objects.requireNonNull(error);
    }

    public static VerseReservationSnapshot pending(VerseReservationId id, String verseName) {
        return new VerseReservationSnapshot(id, verseName, VerseReservationPhase.PENDING, Optional.empty(), Optional.empty());
    }

    public static VerseReservationSnapshot acquired(VerseReservationId id, ReadyVerseLease lease) {
        return new VerseReservationSnapshot(id, lease.verseName(), VerseReservationPhase.ACQUIRED, Optional.of(lease), Optional.empty());
    }

    public static VerseReservationSnapshot failed(VerseReservationId id, String verseName, VerseAccessError error) {
        return new VerseReservationSnapshot(id, verseName, VerseReservationPhase.FAILED, Optional.empty(), Optional.of(error));
    }
}
