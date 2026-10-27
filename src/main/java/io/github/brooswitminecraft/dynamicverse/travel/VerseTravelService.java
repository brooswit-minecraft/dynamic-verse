package io.github.brooswitminecraft.dynamicverse.travel;

import io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessError;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Travel-only consumer of the lifecycle-owned v5 atomic readiness boundary. */
public final class VerseTravelService {
    private final VerseAccessService verses;
    private final PlayerDirectory players;
    private final Movement movement;

    public VerseTravelService(VerseAccessService verses, PlayerDirectory players, Movement movement) {
        this.verses = Objects.requireNonNull(verses);
        this.players = Objects.requireNonNull(players);
        this.movement = Objects.requireNonNull(movement);
    }

    public VerseReservationSnapshot teleportCaller(Player caller, String verseName, Position requested, ResultSink result) {
        return scheduleVerseTravel(caller, verseName, requested, result);
    }

    public VerseReservationSnapshot sendPlayer(Player target, String verseName, Position requested, ResultSink result) {
        return scheduleVerseTravel(target, verseName, requested, result);
    }

    /** Moves the named player to the caller's current position; no verse lease is involved. */
    public void bringPlayer(Player caller, Player target, ResultSink result) {
        UUID callerId = caller.id();
        UUID targetId = target.id();
        players.runLater(() -> {
            Player currentCaller = players.online(callerId);
            Player currentTarget = players.online(targetId);
            if (currentCaller == null || currentTarget == null) {
                result.failed(TravelError.PLAYER_NOT_FOUND);
                return;
            }
            try {
                movement.bring(currentTarget, currentCaller);
                result.moved();
            } catch (RuntimeException failure) {
                result.failed(failure instanceof InvalidCoordinatesException
                        ? TravelError.INVALID_COORDINATES : TravelError.MOVE_FAILED);
            }
        });
    }

    private VerseReservationSnapshot scheduleVerseTravel(Player initialTarget, String verseName, Position requested, ResultSink result) {
        UUID targetId = initialTarget.id();
        TerminalResult terminal = new TerminalResult(result);
        return verses.beginAcquireReadyRead(verseName, snapshot -> completeVerseTravel(snapshot, targetId, requested, terminal));
    }

    private void completeVerseTravel(
            VerseReservationSnapshot snapshot, UUID targetId, Position requested, TerminalResult terminal) {
        if (!terminal.begin()) {
            snapshot.lease().ifPresent(ReadyVerseLease::close);
            return;
        }
        if (snapshot.lease().isEmpty()) {
            terminal.failedAfterBegin(map(snapshot.error().orElse(VerseAccessError.ERR_VERSE_NOT_FOUND)));
            return;
        }
        ReadyVerseLease lease = snapshot.lease().orElseThrow();
        try {
            Player currentTarget = players.online(targetId);
            if (currentTarget == null) {
                terminal.failedAfterBegin(TravelError.PLAYER_NOT_FOUND);
                return;
            }
            // The concrete movement adapter resolves the acquired lease's level identity.
            movement.move(currentTarget, requested, lease);
            terminal.movedAfterBegin();
        } catch (RuntimeException failure) {
            terminal.failedAfterBegin(failure instanceof InvalidCoordinatesException
                    ? TravelError.INVALID_COORDINATES : TravelError.MOVE_FAILED);
        } finally {
            lease.close();
        }
    }

    private static TravelError map(VerseAccessError error) {
        return switch (error) {
            case ERR_VERSE_NOT_FOUND -> TravelError.VERSE_NOT_FOUND;
            case ERR_VERSE_LIFECYCLE_PENDING -> TravelError.VERSE_LIFECYCLE_PENDING;
            case ERR_VERSE_DESTINATION_CHANGED -> TravelError.VERSE_DESTINATION_CHANGED;
        };
    }

    private static final class TerminalResult {
        private final ResultSink delegate;
        private final AtomicBoolean sent = new AtomicBoolean();

        private TerminalResult(ResultSink delegate) { this.delegate = delegate; }
        boolean begin() { return sent.compareAndSet(false, true); }
        void movedAfterBegin() { delegate.moved(); }
        void failedAfterBegin(TravelError error) { delegate.failed(error); }
    }

    public interface PlayerDirectory {
        Player online(UUID id);
        void runLater(Runnable continuation);
    }

    public interface Movement {
        /** A null lease denotes caller-to-caller travel for bring; null position means verse spawn. */
        void move(Player target, Position requested, ReadyVerseLease lease);
        default void bring(Player target, Player caller) { move(target, caller.position(), null); }
    }

    public interface ResultSink {
        void moved();
        void failed(TravelError error);
    }

    public record Player(UUID id, Position position) { }
    public record Position(double x, double y, double z) { }

    public enum TravelError {
        VERSE_NOT_FOUND,
        VERSE_LIFECYCLE_PENDING,
        VERSE_DESTINATION_CHANGED,
        PLAYER_NOT_FOUND,
        MOVE_FAILED,
        INVALID_COORDINATES
    }

    /** Raised by a concrete movement adapter before a teleport reaches Minecraft. */
    public static final class InvalidCoordinatesException extends RuntimeException { }
}
