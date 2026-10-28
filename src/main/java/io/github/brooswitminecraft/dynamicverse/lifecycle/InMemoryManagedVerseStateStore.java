package io.github.brooswitminecraft.dynamicverse.lifecycle;

/** Controlled bootstrap/local-recovery store; production uses the confined file store. */
public final class InMemoryManagedVerseStateStore implements ManagedVerseStateStore {
    private State state = State.empty();
    @Override public synchronized State read() { return state; }
    @Override public synchronized void write(State state) { this.state = state; }
}
