package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosedDimensionDuplicationCoordinatorTest {
    @TempDir Path temporary;

    @Test
    void nonSuccessMismatchAndRepeatedTerminalNeverReachFilesystemPort() {
        RecordingCopier copier = new RecordingCopier(false);
        ClosedDimensionDuplicationCoordinator coordinator = coordinator(copier, intent -> request(intent));
        ClosedDimensionDuplicationIntent intent = intent(4);

        assertRejected(coordinator.duplicate(intent, terminal("source", 4, ClosedDimensionStatus.FAILED_CLOSE, true)));
        assertRejected(coordinator.duplicate(intent, terminal("other", 4, ClosedDimensionStatus.SAVED_AND_CLOSED, false)));
        assertEquals(0, copier.calls.get());

        assertCopied(coordinator.duplicate(intent, success(4)));
        assertRejected(coordinator.duplicate(intent, success(4)));
        assertEquals(1, copier.calls.get());
    }

    @Test
    void exactSuccessPredicateRejectsWrongGenerationInitiallyUnloadedAndFailureMarkedTerminal() {
        RecordingCopier copier = new RecordingCopier(false);
        ClosedDimensionDuplicationCoordinator coordinator = coordinator(copier, this::request);
        ClosedDimensionDuplicationIntent intent = intent(11);

        assertRejected(coordinator.duplicate(intent, terminal("source", 12, true, ClosedDimensionStatus.SAVED_AND_CLOSED, false)));
        assertRejected(coordinator.duplicate(intent, terminal("source", 11, false, ClosedDimensionStatus.SAVED_AND_CLOSED, false)));
        assertRejected(coordinator.duplicate(intent, terminal("source", 11, true, ClosedDimensionStatus.SAVED_AND_CLOSED, true)));
        assertEquals(0, copier.calls.get());
    }

    @Test
    void terminalStageOrCauseCannotContradictFalseFailureFlagAndReachCopier() {
        RecordingCopier copier = new RecordingCopier(false);
        ClosedDimensionDuplicationCoordinator coordinator = coordinator(copier, this::request);
        ClosedDimensionDuplicationIntent intent = intent(12);

        assertRejected(coordinator.duplicate(intent, new ProviderQuiesceCloseTerminal("source", 12, true,
                ClosedDimensionStatus.SAVED_AND_CLOSED, false, "SAVE", null)));
        assertRejected(coordinator.duplicate(intent, new ProviderQuiesceCloseTerminal("source", 12, true,
                ClosedDimensionStatus.SAVED_AND_CLOSED, false, null, new IllegalStateException("close failed"))));
        assertEquals(0, copier.calls.get());
    }

    @Test
    void resolverIdentityMismatchAndDifferentGenerationForClaimedDestinationNeverReachCopy() {
        RecordingCopier mismatchCopier = new RecordingCopier(false);
        ClosedDimensionDuplicationCoordinator mismatch = coordinator(mismatchCopier,
                intent -> new ClosedDimensionCopyRequest(temporary, Path.of("source"), Path.of("destination"), "other", intent.generation()));
        assertUnavailable(mismatch.duplicate(intent(13), success(13)));
        assertEquals(0, mismatchCopier.calls.get());

        RecordingCopier claimedCopier = new RecordingCopier(false);
        ClosedDimensionDuplicationCoordinator claimed = coordinator(claimedCopier, this::request);
        assertCopied(claimed.duplicate(intent(14), success(14)));
        assertRejected(claimed.duplicate(intent(15), success(15)));
        assertEquals(1, claimedCopier.calls.get());
    }

    @Test
    void unsupportedMappingAndFenceRefusalLeaveNoMutationAndMarkDestinationUnavailable() throws Exception {
        Path source = source();
        AtomicInteger calls = new AtomicInteger();
        ClosedDimensionDuplicationCoordinator unsupported = coordinator((request, receipt, fence) -> calls.incrementAndGet(),
                intent -> { throw new ClosedDimensionCopyException("no supported storage route"); });
        ClosedDimensionDuplicationResult unsupportedResult = unsupported.duplicate(intent(5), success(5));
        assertUnavailable(unsupportedResult);
        assertEquals(0, calls.get());
        assertFalse(Files.exists(temporary.resolve("destination")));

        ClosedDimensionDuplicationCoordinator fenced = new ClosedDimensionDuplicationCoordinator(
                intent -> new ClosedDimensionCopyRequest(temporary, Path.of("source"), Path.of("destination"), intent.sourceKey(), intent.generation()),
                new ClosedDimensionCopier(), (key, generation) -> null);
        ClosedDimensionDuplicationResult fencedResult = fenced.duplicate(intent(6), success(6));
        assertUnavailable(fencedResult);
        assertTrue(fenced.isUnavailable("destination"));
        assertFalse(Files.exists(temporary.resolve("destination")));
        assertEquals("marker", Files.readString(source.resolve("marker.txt")));
    }

    @Test
    void copyFailureCannotPublishOrRetryClaimedDestination() {
        RecordingCopier copier = new RecordingCopier(true);
        ClosedDimensionDuplicationCoordinator coordinator = coordinator(copier, this::request);
        ClosedDimensionDuplicationIntent intent = intent(7);

        ClosedDimensionDuplicationResult failed = coordinator.duplicate(intent, success(7));
        assertUnavailable(failed);
        assertFalse(failed.mayLoadDestination());
        assertTrue(coordinator.isUnavailable("destination"));
        assertRejected(coordinator.duplicate(intent, success(7)));
        assertEquals(1, copier.calls.get());
    }

    private ClosedDimensionDuplicationCoordinator coordinator(ClosedDimensionCopyPort copier, DimensionStoragePathResolver resolver) {
        return new ClosedDimensionDuplicationCoordinator(resolver, copier, (key, generation) -> () -> { });
    }

    private ClosedDimensionDuplicationIntent intent(long generation) {
        return new ClosedDimensionDuplicationIntent("source", "destination", generation);
    }

    private ClosedDimensionCopyRequest request(ClosedDimensionDuplicationIntent intent) {
        return new ClosedDimensionCopyRequest(temporary, Path.of("source"), Path.of("destination"), intent.sourceKey(), intent.generation());
    }

    private Path source() throws Exception {
        Path source = temporary.resolve("source");
        Files.createDirectory(source);
        Files.writeString(source.resolve("marker.txt"), "marker");
        return source;
    }

    private static ProviderQuiesceCloseTerminal success(long generation) {
        return terminal("source", generation, true, ClosedDimensionStatus.SAVED_AND_CLOSED, false);
    }

    private static ProviderQuiesceCloseTerminal terminal(String key, long generation, ClosedDimensionStatus status, boolean failure) {
        return terminal(key, generation, true, status, failure);
    }

    private static ProviderQuiesceCloseTerminal terminal(String key, long generation, boolean initiallyLoaded,
                                                          ClosedDimensionStatus status, boolean failure) {
        return new ProviderQuiesceCloseTerminal(key, generation, initiallyLoaded, status, failure);
    }

    private static void assertRejected(ClosedDimensionDuplicationResult result) {
        assertEquals(ClosedDimensionDuplicationResult.Outcome.REJECTED, result.outcome());
        assertFalse(result.mayLoadDestination());
    }

    private static void assertCopied(ClosedDimensionDuplicationResult result) {
        assertEquals(ClosedDimensionDuplicationResult.Outcome.COPIED, result.outcome());
        assertTrue(result.mayLoadDestination());
    }

    private static void assertUnavailable(ClosedDimensionDuplicationResult result) {
        assertEquals(ClosedDimensionDuplicationResult.Outcome.UNAVAILABLE, result.outcome());
        assertFalse(result.mayLoadDestination());
    }

    private static final class RecordingCopier implements ClosedDimensionCopyPort {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean fail;
        private RecordingCopier(boolean fail) { this.fail = fail; }
        @Override public void copy(ClosedDimensionCopyRequest request, ClosedDimensionReceipt receipt, SourceWriteReloadFence fence)
                throws ClosedDimensionCopyException {
            calls.incrementAndGet();
            if (fail) throw new ClosedDimensionCopyException("injected copy failure");
        }
    }
}
