package io.github.brooswitminecraft.dynamicverse.lifecycle;

/** A reservation never blocks the server thread while it is pending. */
public enum VerseReservationPhase {
    PENDING,
    ACQUIRED,
    FAILED
}
