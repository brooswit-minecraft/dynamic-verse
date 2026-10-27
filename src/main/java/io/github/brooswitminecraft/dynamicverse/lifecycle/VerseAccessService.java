package io.github.brooswitminecraft.dynamicverse.lifecycle;

/**
 * Travel-facing atomic readiness API from MINECRAFT-469 v5. Implementations
 * must enqueue and return PENDING; they may only acquire/revalidate the lease
 * from later server-tick work. Travel retains an acquired lease through its
 * movement continuation and closes it in finally.
 */
public interface VerseAccessService {
    VerseReservationSnapshot beginAcquireReadyRead(
            String verseName,
            VerseCompletionSink<VerseReservationSnapshot> sink);
}
