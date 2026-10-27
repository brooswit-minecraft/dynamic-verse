package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.UUID;

public record VerseReservationId(UUID value) {
    public static VerseReservationId newId() {
        return new VerseReservationId(UUID.randomUUID());
    }
}
