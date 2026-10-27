package io.github.brooswitminecraft.dynamicverse.lifecycle;

/**
 * Explicit feasibility boundary: the real provider is intentionally absent.
 * No method in this fixture creates, copies, saves, closes, deletes, or loads
 * a world until the seed and save/close capability evidence is accepted.
 */
public final class FailClosedVerseLifecycleProvider {
    public UnsupportedOperationException create() { return unavailable("create"); }
    public UnsupportedOperationException duplicate() { return unavailable("duplicate"); }
    public UnsupportedOperationException delete() { return unavailable("delete"); }

    private static UnsupportedOperationException unavailable(String operation) {
        return new UnsupportedOperationException(
                operation + " is unavailable: real seed/save-close capability is unproven");
    }
}
