package io.github.brooswitminecraft.dynamicverse.travel;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Travel-only implementation of the MINECRAFT-469 v5 readiness contract.
 *
 * <p>The lifecycle module supplies {@link VerseAccess}; this package neither
 * reads level keys nor defines lifecycle state.  In particular, a destination
 * is used only from the lease supplied by {@code acquireReadyRead}.</p>
 */
public final class VerseTravelService {
    private final VerseAccess verses;
    private final PlayerDirectory players;
    private final Movement movement;

    public VerseTravelService(VerseAccess verses, PlayerDirectory players, Movement movement) {
        this.verses = Objects.requireNonNull(verses);
        this.players = Objects.requireNonNull(players);
        this.movement = Objects.requireNonNull(movement);
    }

    public void teleportCaller(Player caller, String verseName, Position requested, ResultSink result) {
        scheduleVerseTravel(caller, verseName, requested, result);
    }

    public void sendPlayer(Player target, String verseName, Position requested, ResultSink result) {
        scheduleVerseTravel(target, verseName, requested, result);
    }

    /** Moves the named player to the caller's position; no verse lease is involved. */
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
                movement.move(currentTarget, currentCaller.position(), null);
                result.moved();
            } catch (RuntimeException failure) {
                result.failed(TravelError.MOVE_FAILED);
            }
        });
    }

    private void scheduleVerseTravel(Player initialTarget, String verseName, Position requested, ResultSink result) {
        UUID targetId = initialTarget.id();
        TerminalResult terminal = new TerminalResult(result);
        verses.acquireReadyRead(verseName, new VerseAccess.AcquireCallback() {
            @Override
            public void acquired(ReadyVerseLease lease) {
                if (!terminal.begin()) {
                    lease.close();
                    return;
                }
                try {
                    Player currentTarget = players.online(targetId);
                    if (currentTarget == null) {
                        terminal.failedAfterBegin(TravelError.PLAYER_NOT_FOUND);
                        return;
                    }
                    // The lease's world and spawn were revalidated atomically by lifecycle.
                    movement.move(currentTarget, requested == null ? lease.spawn() : requested, lease);
                    terminal.movedAfterBegin();
                } catch (RuntimeException failure) {
                    terminal.failedAfterBegin(TravelError.MOVE_FAILED);
                } finally {
                    lease.close();
                }
            }

            @Override
            public void failed(TravelError error) {
                if (terminal.begin()) terminal.failedAfterBegin(error);
            }
        });
    }

    /** Defends command feedback against a faulty or stale asynchronous producer. */
    private static final class TerminalResult {
        private final ResultSink delegate;
        private final AtomicBoolean sent = new AtomicBoolean();

        private TerminalResult(ResultSink delegate) { this.delegate = delegate; }
        boolean begin() { return sent.compareAndSet(false, true); }
        void movedAfterBegin() { delegate.moved(); }
        void failedAfterBegin(TravelError error) { delegate.failed(error); }
    }

    public interface VerseAccess {
        /** Nonblocking v5 acquire: completion occurs later, with one terminal callback. */
        void acquireReadyRead(String verseName, AcquireCallback callback);

        interface AcquireCallback {
            void acquired(ReadyVerseLease lease);
            void failed(TravelError error);
        }
    }

    public interface ReadyVerseLease extends AutoCloseable {
        Position spawn();
        long generation();
        @Override void close();
    }

    public interface PlayerDirectory {
        Player online(UUID id);
        void runLater(Runnable continuation);
    }

    public interface Movement {
        /** A null lease denotes caller-to-caller travel for bring. */
        void move(Player target, Position destination, ReadyVerseLease lease);
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
        MOVE_FAILED
    }
}
