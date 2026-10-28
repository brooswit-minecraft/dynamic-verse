package io.github.brooswitminecraft.dynamicverse.travel;

import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot;
import java.util.Objects;

/** Production command execution seam; registrars retain their platform-specific argument parsers. */
public final class VerseTravelCommandExecutor<S> {
    public interface Context<S> {
        VerseTravelService travel(S source);
        void success(S source, String message);
        void failure(S source, String message);
    }

    private final Context<S> context;

    public VerseTravelCommandExecutor(Context<S> context) { this.context = Objects.requireNonNull(context); }

    public int bring(S source, VerseTravelService.Player caller, VerseTravelService.Player target) {
        if (caller == null) return fail(source, VerseTravelService.TravelError.SOURCE_REQUIRES_PLAYER);
        if (target == null) return fail(source, VerseTravelService.TravelError.PLAYER_NOT_FOUND);
        context.success(source, "travel accepted/pending");
        context.travel(source).bringPlayer(caller, target, sink(source));
        return 1;
    }

    public int send(S source, VerseTravelService.Player target, String name, VerseTravelService.Position requested) {
        if (target == null) return fail(source, VerseTravelService.TravelError.PLAYER_NOT_FOUND);
        if (requested != null && !TravelCoordinates.isValid(requested)) return fail(source, VerseTravelService.TravelError.INVALID_COORDINATES);
        VerseReservationSnapshot admission = context.travel(source).sendPlayer(target, name, requested, sink(source));
        context.success(source, "travel accepted/pending " + admission.id().value());
        return 1;
    }

    public int tp(S source, VerseTravelService.Player caller, String name, VerseTravelService.Position requested) {
        if (caller == null) return fail(source, VerseTravelService.TravelError.SOURCE_REQUIRES_PLAYER);
        if (requested != null && !TravelCoordinates.isValid(requested)) return fail(source, VerseTravelService.TravelError.INVALID_COORDINATES);
        VerseReservationSnapshot admission = context.travel(source).teleportCaller(caller, name, requested, sink(source));
        context.success(source, "travel accepted/pending " + admission.id().value());
        return 1;
    }

    private int fail(S source, VerseTravelService.TravelError error) { context.failure(source, "travel failed: " + error); return 0; }
    private VerseTravelService.ResultSink sink(S source) { return new VerseTravelService.ResultSink() {
        @Override public void moved() { context.success(source, "travel moved"); }
        @Override public void failed(VerseTravelService.TravelError error) { context.failure(source, "travel failed: " + error); }
    }; }
}
