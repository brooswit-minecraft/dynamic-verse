package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A read lease held by travel from revalidation through movement. The current
 * feasibility fixture deliberately represents a level by its stable identity,
 * rather than creating or loading a real ServerLevel.
 */
public final class ReadyVerseLease implements AutoCloseable {
    private final String verseName;
    private final String levelIdentity;
    private final long generation;
    private final VerseReservationId reservationId;
    private final Runnable release;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReadyVerseLease(
            String verseName,
            String levelIdentity,
            long generation,
            VerseReservationId reservationId,
            Runnable release) {
        this.verseName = Objects.requireNonNull(verseName);
        this.levelIdentity = Objects.requireNonNull(levelIdentity);
        this.generation = generation;
        this.reservationId = Objects.requireNonNull(reservationId);
        this.release = Objects.requireNonNull(release);
    }

    public String verseName() { return verseName; }
    public String levelIdentity() { return levelIdentity; }
    public long generation() { return generation; }
    public VerseReservationId reservationId() { return reservationId; }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            release.run();
        }
    }
}
