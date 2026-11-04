package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationObservationQueueTest {
    @Test void completesOnlyOnALaterObservationTickAndRemovesItself() {
        RegistrationObservationQueue queue = new RegistrationObservationQueue();
        AtomicBoolean current = new AtomicBoolean(); AtomicInteger completions = new AtomicInteger();
        queue.await(current::get, value -> { assertTrue(value); completions.incrementAndGet(); });
        assertEquals(1, queue.pendingCount()); assertEquals(0, completions.get());
        queue.observeTick(); assertEquals(0, completions.get());
        current.set(true); queue.observeTick();
        assertEquals(1, completions.get()); assertEquals(0, queue.pendingCount());
    }

    @Test void timesOutOnlyAfterTheBoundedNumberOfObservationTicks() {
        RegistrationObservationQueue queue = new RegistrationObservationQueue();
        AtomicInteger result = new AtomicInteger();
        queue.await(() -> false, value -> result.set(value ? 1 : -1));
        for (int i = 0; i < 39; i++) { queue.observeTick(); assertEquals(0, result.get()); }
        queue.observeTick(); assertEquals(-1, result.get()); assertEquals(0, queue.pendingCount());
    }

    @Test void retirementFailsPendingObserversAndRefusesNewSuccess() {
        RegistrationObservationQueue queue = new RegistrationObservationQueue();
        AtomicInteger result = new AtomicInteger();
        queue.await(() -> true, value -> result.set(value ? 1 : -1));
        queue.retire();
        assertEquals(-1, result.get()); assertEquals(0, queue.pendingCount());
        queue.await(() -> true, value -> result.set(value ? 2 : -2));
        assertEquals(-2, result.get());
    }
}
