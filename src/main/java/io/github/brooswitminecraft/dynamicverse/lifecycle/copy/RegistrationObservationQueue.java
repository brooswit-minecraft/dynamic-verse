package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Production-used bounded later-tick observer. Call {@link #observeTick()} only from ServerTickEvent.Post. */
final class RegistrationObservationQueue {
    private static final int MAX_OBSERVATION_TICKS = 40;
    private final List<Observation> observers = new ArrayList<>();

    synchronized void await(BooleanSupplier current, Consumer<Boolean> completion) {
        observers.add(new Observation(current, completion, MAX_OBSERVATION_TICKS));
    }

    void observeTick() {
        List<Completion> completed = new ArrayList<>();
        synchronized (this) {
            for (int i = observers.size() - 1; i >= 0; i--) {
                Observation observation = observers.get(i);
                if (observation.current.getAsBoolean()) {
                    observers.remove(i); completed.add(new Completion(observation.completion, true));
                } else if (--observation.remaining == 0) {
                    observers.remove(i); completed.add(new Completion(observation.completion, false));
                }
            }
        }
        completed.forEach(result -> result.completion.accept(result.current));
    }

    synchronized int pendingCount() { return observers.size(); }

    private static final class Observation {
        private final BooleanSupplier current; private final Consumer<Boolean> completion; private int remaining;
        private Observation(BooleanSupplier current, Consumer<Boolean> completion, int remaining) {
            this.current = current; this.completion = completion; this.remaining = remaining;
        }
    }
    private record Completion(Consumer<Boolean> completion, boolean current) { }
}
