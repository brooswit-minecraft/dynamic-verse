package io.github.brooswitminecraft.dynamicverse.lifecycle;

/** Test-only construction bridge for platform-boundary consumers of the published lease API. */
public final class ReadyVerseLeaseFixtures {
    private ReadyVerseLeaseFixtures() { }

    public static ReadyVerseLease acquired(
            String verseName, String levelIdentity, long generation, VerseReservationId reservationId, Runnable release) {
        return new ReadyVerseLease(verseName, levelIdentity, generation, reservationId, release);
    }
}
