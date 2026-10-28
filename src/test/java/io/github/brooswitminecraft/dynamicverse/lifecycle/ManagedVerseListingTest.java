package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedVerseListingTest {
    @Test
    void pendingAndQuarantinedCannotLeakLiveIdentity() {
        assertDoesNotThrow(() -> new ManagedVerseListing("dynamicverse:pending", ManagedVerseListing.State.PENDING, Optional.empty(), Optional.empty()));
        assertDoesNotThrow(() -> new ManagedVerseListing("dynamicverse:failed", ManagedVerseListing.State.QUARANTINED, Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ManagedVerseListing("dynamicverse:pending", ManagedVerseListing.State.PENDING, Optional.of("level"), Optional.of(1L)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedVerseListing("dynamicverse:ready", ManagedVerseListing.State.READY, Optional.empty(), Optional.empty()));
    }
}
