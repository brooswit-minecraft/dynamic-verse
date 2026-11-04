package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import io.github.brooswitminecraft.dynamicverse.travel.VerseTravelCommandExecutor;
import io.github.brooswitminecraft.dynamicverse.travel.VerseTravelService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VerseTravelCommandExecutorTest {
    @Test void inlineFailureFollowsAdmissionForSendAndTeleport() {
        for (boolean teleport : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.inline = VerseReservationSnapshot.failed(f.id, "moon", VerseAccessError.ERR_VERSE_NOT_FOUND);
            assertEquals(1, f.execute(teleport));
            assertEquals(List.of(f.pending(), "travel failed: VERSE_NOT_FOUND"), f.feedback);
        }
    }

    @Test void inlineSuccessFollowsAdmissionAndReleasesLeaseForBothCommands() {
        for (boolean teleport : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            f.inline = VerseReservationSnapshot.acquired(f.id,
                    new ReadyVerseLease("moon", "moon-level", 1L, f.id, () -> f.releases++));
            assertEquals(1, f.execute(teleport));
            assertEquals(List.of(f.pending(), "travel moved"), f.feedback);
            assertEquals(1, f.moves);
            assertEquals(1, f.releases);
        }
    }

    @Test void deferredCompletionAndDuplicateCallbackKeepOneOrderedTerminalResult() {
        Fixture f = new Fixture();
        assertEquals(1, f.execute(false));
        assertEquals(List.of(f.pending()), f.feedback);
        var failed = VerseReservationSnapshot.failed(f.id, "moon", VerseAccessError.ERR_VERSE_LIFECYCLE_PENDING);
        f.callback.complete(failed);
        f.callback.complete(failed);
        assertEquals(List.of(f.pending(), "travel failed: VERSE_LIFECYCLE_PENDING"), f.feedback);
        assertEquals(0, f.moves);
    }

    private static final class Fixture {
        final VerseReservationId id = VerseReservationId.newId();
        final VerseTravelService.Player player = new VerseTravelService.Player(UUID.randomUUID(),
                new VerseTravelService.Position(0, 64, 0));
        final List<String> feedback = new ArrayList<>();
        VerseReservationSnapshot inline;
        VerseCompletionSink<VerseReservationSnapshot> callback;
        int moves;
        int releases;
        final VerseAccessService access = (name, sink) -> {
            callback = sink;
            if (inline != null) sink.complete(inline);
            return VerseReservationSnapshot.pending(id, name);
        };
        final VerseTravelService service = new VerseTravelService(access, new VerseTravelService.PlayerDirectory() {
            public VerseTravelService.Player online(UUID playerId) { return player; }
            public void runLater(Runnable action) { action.run(); }
        }, (target, destination, lease) -> moves++);
        final VerseTravelCommandExecutor<String> executor = new VerseTravelCommandExecutor<>(new VerseTravelCommandExecutor.Context<>() {
            public VerseTravelService travel(String source) { return service; }
            public void success(String source, String message) { feedback.add(message); }
            public void failure(String source, String message) { feedback.add(message); }
        });
        String pending() { return "travel accepted/pending " + id.value(); }
        int execute(boolean teleport) {
            return teleport ? executor.tp("test", player, "moon", null) : executor.send("test", player, "moon", null);
        }
    }
}
