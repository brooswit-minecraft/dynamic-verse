package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

/**
 * A coordinator outcome. Only COPIED may be handed to separately reviewed
 * restore/destination-load wiring; this coordinator never loads or publishes.
 */
public record ClosedDimensionDuplicationResult(Outcome outcome, String detail, Throwable cause,
                                               ClosedDimensionCopyRequest copyRequest) {
    public ClosedDimensionDuplicationResult(Outcome outcome, String detail) {
        this(outcome, detail, null, null);
    }
    public ClosedDimensionDuplicationResult(Outcome outcome, String detail, Throwable cause) {
        this(outcome, detail, cause, null);
    }
    public enum Outcome { REJECTED, COPIED, UNAVAILABLE }

    public boolean mayLoadDestination() {
        return outcome == Outcome.COPIED;
    }
}
