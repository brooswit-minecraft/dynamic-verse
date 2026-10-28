package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

/**
 * A coordinator outcome. Only COPIED may be handed to separately reviewed
 * restore/destination-load wiring; this coordinator never loads or publishes.
 */
public record ClosedDimensionDuplicationResult(Outcome outcome, String detail) {
    public enum Outcome { REJECTED, COPIED, UNAVAILABLE }

    public boolean mayLoadDestination() {
        return outcome == Outcome.COPIED;
    }
}
