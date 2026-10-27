package io.github.brooswitminecraft.dynamicverse.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerseTravelServiceTest {
    @Test
    void sendUsesAcquiredLeaseAndRequestedCoordinates() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", new VerseTravelService.Position(2, 70, -3), fixture.result);

        fixture.access.callback.acquired(fixture.lease);

        assertEquals(fixture.target, fixture.movedPlayer);
        assertEquals(new VerseTravelService.Position(2, 70, -3), fixture.movedTo);
        assertEquals(fixture.lease, fixture.movedLease);
        assertEquals(1, fixture.result.moves);
        assertTrue(fixture.lease.closed);
    }

    @Test
    void teleportDefaultsToSpawnAndReleasesLeaseWhenMoveFails() {
        Fixture fixture = new Fixture();
        fixture.movementFailure = true;
        fixture.service.teleportCaller(fixture.target, "moon", null, fixture.result);

        fixture.access.callback.acquired(fixture.lease);

        assertEquals(fixture.lease.spawn(), fixture.movedTo);
        assertEquals(VerseTravelService.TravelError.MOVE_FAILED, fixture.result.error);
        assertTrue(fixture.lease.closed);
    }

    @Test
    void pendingOrReplacedVerseTerminatesWithoutMoving() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", null, fixture.result);

        fixture.access.callback.failed(VerseTravelService.TravelError.VERSE_DESTINATION_CHANGED);

        assertEquals(VerseTravelService.TravelError.VERSE_DESTINATION_CHANGED, fixture.result.error);
        assertNull(fixture.movedPlayer);
    }

    @Test
    void staleSecondCallbackDoesNotProduceSecondTerminalResult() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", null, fixture.result);

        fixture.access.callback.failed(VerseTravelService.TravelError.VERSE_LIFECYCLE_PENDING);
        fixture.access.callback.acquired(fixture.lease);

        assertEquals(VerseTravelService.TravelError.VERSE_LIFECYCLE_PENDING, fixture.result.error);
        assertEquals(0, fixture.result.moves);
        assertNull(fixture.movedPlayer);
    }

    @Test
    void offlinePlayerAtContinuationDoesNotMoveAndReleasesLease() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", null, fixture.result);
        fixture.players.remove(fixture.target.id());

        fixture.access.callback.acquired(fixture.lease);

        assertEquals(VerseTravelService.TravelError.PLAYER_NOT_FOUND, fixture.result.error);
        assertNull(fixture.movedPlayer);
        assertTrue(fixture.lease.closed);
    }

    @Test
    void bringRevalidatesBothPlayersAndUsesCallerPosition() {
        Fixture fixture = new Fixture();
        fixture.service.bringPlayer(fixture.caller, fixture.target, fixture.result);

        assertEquals(fixture.target, fixture.movedPlayer);
        assertEquals(fixture.caller.position(), fixture.movedTo);
        assertNull(fixture.movedLease);
        assertEquals(1, fixture.result.moves);
    }

    private static final class Fixture {
        final VerseTravelService.Player caller = new VerseTravelService.Player(UUID.randomUUID(), new VerseTravelService.Position(1, 65, 1));
        final VerseTravelService.Player target = new VerseTravelService.Player(UUID.randomUUID(), new VerseTravelService.Position(0, 64, 0));
        final Players players = new Players(caller, target);
        final Access access = new Access();
        final Lease lease = new Lease(new VerseTravelService.Position(10, 80, 10));
        final Result result = new Result();
        boolean movementFailure;
        VerseTravelService.Player movedPlayer;
        VerseTravelService.Position movedTo;
        VerseTravelService.ReadyVerseLease movedLease;
        final VerseTravelService service = new VerseTravelService(access, players, (player, destination, leaseArgument) -> {
            movedPlayer = player;
            movedTo = destination;
            movedLease = leaseArgument;
            if (movementFailure) throw new IllegalStateException("movement failed");
        });
    }

    private static final class Access implements VerseTravelService.VerseAccess {
        AcquireCallback callback;
        @Override public void acquireReadyRead(String verseName, AcquireCallback callback) { this.callback = callback; }
    }

    private static final class Lease implements VerseTravelService.ReadyVerseLease {
        private final VerseTravelService.Position spawn;
        boolean closed;
        Lease(VerseTravelService.Position spawn) { this.spawn = spawn; }
        @Override public VerseTravelService.Position spawn() { return spawn; }
        @Override public long generation() { return 7L; }
        @Override public void close() { closed = true; }
    }

    private static final class Players implements VerseTravelService.PlayerDirectory {
        private final Map<UUID, VerseTravelService.Player> online = new HashMap<>();
        Players(VerseTravelService.Player... players) { for (var player : players) online.put(player.id(), player); }
        void remove(UUID id) { online.remove(id); }
        @Override public VerseTravelService.Player online(UUID id) { return online.get(id); }
        @Override public void runLater(Runnable continuation) { continuation.run(); }
    }

    private static final class Result implements VerseTravelService.ResultSink {
        int moves;
        VerseTravelService.TravelError error;
        @Override public void moved() { moves++; }
        @Override public void failed(VerseTravelService.TravelError error) { this.error = error; }
    }
}
