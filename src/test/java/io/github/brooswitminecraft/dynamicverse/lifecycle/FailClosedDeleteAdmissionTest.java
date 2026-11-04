package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ordinary local coverage of the same production admission policy; no provider or filesystem port exists here. */
class FailClosedDeleteAdmissionTest {
    @Test
    void onlyReadyDynamicVerseOwnershipReachesSafeDeleteUnavailableWithoutMutation() {
        AtomicInteger ownershipReads = new AtomicInteger();
        FailClosedDeleteAdmission.OwnedVerseLookup ownedReady = new FailClosedDeleteAdmission.OwnedVerseLookup() {
            @Override public boolean isOwned(String id) {
                ownershipReads.incrementAndGet();
                return id.equals("dynamicverse:owned") || id.equals("dynamicverse:pending");
            }
            @Override public boolean isReady(String id) { return id.equals("dynamicverse:owned"); }
        };

        assertEquals(VerseLifecycleCommandService.DeleteAdmission.ERR_SAFE_DELETE_UNAVAILABLE,
                FailClosedDeleteAdmission.validate("owned", ownedReady));
        assertEquals(VerseLifecycleCommandService.DeleteAdmission.ERR_SAFE_DELETE_UNAVAILABLE,
                FailClosedDeleteAdmission.validate("dynamicverse:owned", ownedReady));
        assertEquals(VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_MANAGED,
                FailClosedDeleteAdmission.validate("dynamicverse:orphan", ownedReady));
        assertEquals(VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_MANAGED,
                FailClosedDeleteAdmission.validate("minecraft:overworld", ownedReady));
        assertEquals(VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_MANAGED,
                FailClosedDeleteAdmission.validate("bad?", ownedReady));
        assertEquals(VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_READY,
                FailClosedDeleteAdmission.validate("pending", ownedReady));

        assertEquals(4, ownershipReads.get(), "foreign/malformed spelling must not probe ownership");
    }
}
