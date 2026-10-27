package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.brooswitminecraft.dynamicverse.travel.VerseTravelService;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerseTravelServiceTest {
    @Test
    void sendUsesAcquiredLifecycleLeaseAndRequestedCoordinates() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", new VerseTravelService.Position(2, 70, -3), fixture.result);

        fixture.access.complete(VerseReservationSnapshot.acquired(fixture.id, fixture.lease));

        assertEquals(fixture.target, fixture.movedPlayer);
        assertEquals(new VerseTravelService.Position(2, 70, -3), fixture.movedTo);
        assertEquals(fixture.lease, fixture.movedLease);
        assertEquals(1, fixture.result.moves);
        assertTrue(fixture.released);
    }

    @Test
    void teleportPassesNullPositionForAcquiredVerseSpawnAndReleasesOnFailure() {
        Fixture fixture = new Fixture();
        fixture.movementFailure = true;
        fixture.service.teleportCaller(fixture.target, "moon", null, fixture.result);

        fixture.access.complete(VerseReservationSnapshot.acquired(fixture.id, fixture.lease));

        assertNull(fixture.movedTo);
        assertEquals(VerseTravelService.TravelError.MOVE_FAILED, fixture.result.error);
        assertTrue(fixture.released);
    }

    @Test
    void pendingOrReplacedVerseTerminatesWithoutMoving() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", null, fixture.result);

        fixture.access.complete(VerseReservationSnapshot.failed(
                fixture.id, "moon", VerseAccessError.ERR_VERSE_DESTINATION_CHANGED));

        assertEquals(VerseTravelService.TravelError.VERSE_DESTINATION_CHANGED, fixture.result.error);
        assertNull(fixture.movedPlayer);
    }

    @Test
    void staleAcquiredCallbackReleasesLeaseWithoutMoving() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", null, fixture.result);
        fixture.access.complete(VerseReservationSnapshot.failed(
                fixture.id, "moon", VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING));
        fixture.access.complete(VerseReservationSnapshot.acquired(fixture.id, fixture.lease));

        assertEquals(VerseTravelService.TravelError.VERSE_LIFECYCLE_PENDING, fixture.result.error);
        assertEquals(0, fixture.result.moves);
        assertNull(fixture.movedPlayer);
        assertTrue(fixture.released);
    }

    @Test
    void offlinePlayerAtContinuationDoesNotMoveAndReleasesLease() {
        Fixture fixture = new Fixture();
        fixture.service.sendPlayer(fixture.target, "moon", null, fixture.result);
        fixture.players.remove(fixture.target.id());

        fixture.access.complete(VerseReservationSnapshot.acquired(fixture.id, fixture.lease));

        assertEquals(VerseTravelService.TravelError.PLAYER_NOT_FOUND, fixture.result.error);
        assertNull(fixture.movedPlayer);
        assertTrue(fixture.released);
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
        final VerseReservationId id = VerseReservationId.newId();
        boolean released;
        final ReadyVerseLease lease = new ReadyVerseLease("moon", "moon-level", 7L, id, () -> released = true);
        final Result result = new Result();
        boolean movementFailure;
        VerseTravelService.Player movedPlayer;
        VerseTravelService.Position movedTo;
        ReadyVerseLease movedLease;
        final VerseTravelService service = new VerseTravelService(access, players, (player, destination, leaseArgument) -> {
            movedPlayer = player;
            movedTo = destination;
            movedLease = leaseArgument;
            if (movementFailure) throw new IllegalStateException("movement failed");
        });
    }

    private static final class Access implements VerseAccessService {
        VerseCompletionSink<VerseReservationSnapshot> callback;
        @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> callback) {
            this.callback = callback;
            return VerseReservationSnapshot.pending(VerseReservationId.newId(), verseName);
        }
        void complete(VerseReservationSnapshot result) { callback.complete(result); }
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
