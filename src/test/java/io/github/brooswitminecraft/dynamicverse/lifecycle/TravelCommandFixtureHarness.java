package io.github.brooswitminecraft.dynamicverse.lifecycle;

import com.mojang.brigadier.CommandDispatcher;
import io.github.brooswitminecraft.dynamicverse.travel.TravelCommandFixtureRegistrar;
import io.github.brooswitminecraft.dynamicverse.travel.TravelCoordinates;
import io.github.brooswitminecraft.dynamicverse.travel.VerseTravelService;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** No-server JUnit coverage for the production-used command executor and disposable dispatcher adapter. */
final class TravelCommandFixtureHarness {
    record Source(boolean permitted, boolean player, boolean failingMove, List<String> messages) { }
    static final class Access implements VerseAccessService {
        VerseCompletionSink<VerseReservationSnapshot> sink; final VerseReservationId id = VerseReservationId.newId(); int admissions;
        public VerseReservationSnapshot beginAcquireReadyRead(String name, VerseCompletionSink<VerseReservationSnapshot> callback) { admissions++; sink = callback; return VerseReservationSnapshot.pending(id, name); }
        void fail(String name) { sink.complete(VerseReservationSnapshot.failed(id, name, VerseAccessError.ERR_VERSE_NOT_FOUND)); }
    }
    static final class Directory implements VerseTravelService.PlayerDirectory {
        boolean queued; Runnable pending; final Map<UUID, VerseTravelService.Player> players = new HashMap<>();
        public VerseTravelService.Player online(UUID id) { return players.get(id); }
        public void runLater(Runnable action) { if (queued) pending = action; else action.run(); }
    }
    record Move(UUID playerId, VerseTravelService.Position requested, VerseTravelService.Position resolved, ReadyVerseLease lease) { }

    @Test
    void registeredHandlersPreserveTravelCommandContract() throws Exception {
        Access access = new Access(); Directory directory = new Directory();
        var caller = new VerseTravelService.Player(UUID.randomUUID(), new VerseTravelService.Position(1, 64, 1));
        var target = new VerseTravelService.Player(UUID.randomUUID(), new VerseTravelService.Position(2, 64, 2));
        var defaultSpawn = new VerseTravelService.Position(10, 70, -10);
        directory.players.put(caller.id(), caller); directory.players.put(target.id(), target);
        final List<Move> moves = new ArrayList<>();
        var service = new VerseTravelService(access, directory, (player, destination, lease) ->
                moves.add(new Move(player.id(), destination, destination == null ? defaultSpawn : destination, lease)));
        Access failedAccess = new Access(); final int[] released = {0};
        var failedService = new VerseTravelService(failedAccess, directory, (player, destination, lease) -> { throw new IllegalStateException("canceled"); });
        var context = new TravelCommandFixtureRegistrar.Context<Source>() {
            public boolean permitted(Source source) { return source.permitted(); }
            public VerseTravelService.Player caller(Source source) { return source.player() ? caller : null; }
            public VerseTravelService.Player player(String name) { return name.equals("target") ? target : null; }
            public VerseTravelService travel(Source source) { return source.failingMove() ? failedService : service; }
            public void success(Source source, String message) { source.messages().add(message); }
            public void failure(Source source, String message) { source.messages().add(message); }
        };
        var dispatcher = new CommandDispatcher<Source>(); new TravelCommandFixtureRegistrar<>(context).register(dispatcher);
        Source inline = new Source(true, true, false, new ArrayList<>()); dispatcher.execute("verse bring target", inline);
        assertMessages(inline, "travel accepted/pending", "travel moved");
        assertEquals(new Move(target.id(), caller.position(), caller.position(), null), moves.get(0));
        Source queued = new Source(true, true, false, new ArrayList<>()); directory.queued = true; dispatcher.execute("verse bring target", queued);
        assertMessages(queued, "travel accepted/pending"); directory.pending.run(); assertMessages(queued, "travel accepted/pending", "travel moved");
        Source commandBlock = new Source(true, false, false, new ArrayList<>()); directory.queued = false; dispatcher.execute("verse send target moon", commandBlock); access.fail("moon");
        if (!commandBlock.messages().get(0).startsWith("travel accepted/pending") || !commandBlock.messages().get(1).contains("VERSE_NOT_FOUND")) throw new AssertionError(commandBlock.messages());
        Source failedMove = new Source(true, false, true, new ArrayList<>()); dispatcher.execute("verse send target moon", failedMove);
        ReadyVerseLease lease = new ReadyVerseLease("moon", "level", 1L, failedAccess.id, () -> released[0]++);
        failedAccess.sink.complete(VerseReservationSnapshot.acquired(failedAccess.id, lease));
        assertEquals(1, released[0]); assertFalse(failedMove.messages().contains("travel moved")); assertEquals("travel failed: MOVE_FAILED", failedMove.messages().get(1));
        Source defaultPosition = new Source(true, false, false, new ArrayList<>()); dispatcher.execute("verse send target moon", defaultPosition);
        int[] defaultRelease = {0}; ReadyVerseLease defaultLease = new ReadyVerseLease("moon", "level", 1L, access.id, () -> defaultRelease[0]++); access.sink.complete(VerseReservationSnapshot.acquired(access.id, defaultLease));
        assertEquals(new Move(target.id(), null, defaultSpawn, defaultLease), moves.get(moves.size() - 1)); assertEquals(1, defaultRelease[0]); assertEquals("travel moved", defaultPosition.messages().get(1));
        Source acceptedBoundary = new Source(true, false, false, new ArrayList<>()); dispatcher.execute("verse send target moon -30000000 -20000000 29999999", acceptedBoundary);
        VerseTravelService.Position boundary = new VerseTravelService.Position(-30_000_000, -20_000_000, 29_999_999);
        int[] boundaryRelease = {0}; ReadyVerseLease boundaryLease = new ReadyVerseLease("moon", "level", 1L, access.id, () -> boundaryRelease[0]++); access.sink.complete(VerseReservationSnapshot.acquired(access.id, boundaryLease));
        assertEquals(new Move(target.id(), boundary, boundary, boundaryLease), moves.get(moves.size() - 1)); assertEquals(1, boundaryRelease[0]); assertEquals("travel moved", acceptedBoundary.messages().get(1));
        int admissionsBeforeInvalid = access.admissions; int movementsBeforeInvalid = moves.size();
        Source tooFarX = new Source(true, false, false, new ArrayList<>()); dispatcher.execute("verse send target moon 30000000 64 0", tooFarX);
        Source tooHighY = new Source(true, false, false, new ArrayList<>()); dispatcher.execute("verse send target moon 0 20000000 0", tooHighY);
        assertEquals(List.of("travel failed: INVALID_COORDINATES"), tooFarX.messages()); assertEquals(List.of("travel failed: INVALID_COORDINATES"), tooHighY.messages()); assertEquals(admissionsBeforeInvalid, access.admissions); assertEquals(movementsBeforeInvalid, moves.size());
        Source noPlayer = new Source(true, false, false, new ArrayList<>()); dispatcher.execute("verse tp moon", noPlayer); if (!noPlayer.messages().get(0).contains("SOURCE_REQUIRES_PLAYER")) throw new AssertionError(noPlayer.messages());
        Source noPlayerBring = new Source(true, false, false, new ArrayList<>()); dispatcher.execute("verse bring target", noPlayerBring); assertEquals(List.of("travel failed: SOURCE_REQUIRES_PLAYER"), noPlayerBring.messages());
        assertThrows(Exception.class, () -> dispatcher.execute("verse bring target", new Source(false, true, false, new ArrayList<>())));
        for (int axis = 0; axis < 3; axis++) for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            double[] coordinates = {0, 64, 0}; coordinates[axis] = value;
            assertFalse(TravelCoordinates.isValid(new VerseTravelService.Position(coordinates[0], coordinates[1], coordinates[2])));
        }
    }
    static void assertMessages(Source source, String... expected) { if (!source.messages().equals(List.of(expected))) throw new AssertionError(source.messages()); }
}
