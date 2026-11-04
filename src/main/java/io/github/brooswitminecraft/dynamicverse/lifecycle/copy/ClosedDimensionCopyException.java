package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

/** Fail-closed copy refusal. Partial destinations are deliberately retained. */
public final class ClosedDimensionCopyException extends Exception {
    public ClosedDimensionCopyException(String message) { super(message); }
    public ClosedDimensionCopyException(String message, Throwable cause) { super(message, cause); }
}
