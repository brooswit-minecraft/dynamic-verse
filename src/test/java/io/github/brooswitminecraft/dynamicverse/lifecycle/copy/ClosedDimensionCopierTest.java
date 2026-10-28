package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClosedDimensionCopierTest {
    @TempDir Path temporary;

    @Test
    void copiesExactNestedDimensionDirectoryWithoutChangingSource() throws Exception {
        Path source = sourceWithNestedMarker();
        AtomicBoolean released = new AtomicBoolean();
        new ClosedDimensionCopier().copy(request("source", "destination", 7), success(7), (key, generation) -> () -> released.set(true));

        assertEquals("marker", Files.readString(temporary.resolve("destination/nested/marker.txt")));
        assertEquals("marker", Files.readString(source.resolve("nested/marker.txt")));
        assertTrue(released.get());
        assertFalse(Files.exists(temporary.resolve("server.properties")));
    }

    @Test
    void rejectsExistingDestinationOverlapAndTraversal() throws Exception {
        sourceWithNestedMarker();
        Files.createDirectory(temporary.resolve("destination"));
        ClosedDimensionCopier copier = new ClosedDimensionCopier();
        assertThrows(ClosedDimensionCopyException.class, () -> copier.copy(request("source", "destination", 1), success(1), fence()));
        assertThrows(ClosedDimensionCopyException.class, () -> new ClosedDimensionCopier().copy(request("source", "source/nested", 2), success(2), fence()));
        assertThrows(ClosedDimensionCopyException.class, () -> new ClosedDimensionCopier().copy(request("../outside", "destination-two", 3), success(3), fence()));
        assertFalse(Files.exists(temporary.resolve("destination-two")));
    }

    @Test
    void rejectsLinksAndSpecialFiles() throws Exception {
        Path source = sourceWithNestedMarker();
        Path target = temporary.resolve("outside");
        Files.createDirectory(target);
        try {
            Files.createSymbolicLink(source.resolve("linked"), target);
        } catch (UnsupportedOperationException e) {
            return; // The component still rejects links on a provider that exposes them.
        }
        assertThrows(ClosedDimensionCopyException.class, () -> new ClosedDimensionCopier().copy(request("source", "destination", 1), success(1), fence()));

        Files.delete(source.resolve("linked"));
        Process process = new ProcessBuilder("/usr/bin/mkfifo", source.resolve("fifo").toString()).start();
        assertEquals(0, process.waitFor());
        assertThrows(ClosedDimensionCopyException.class, () -> new ClosedDimensionCopier().copy(request("source", "destination-two", 2), success(2), fence()));
    }

    @Test
    void rejectsLinkedStorageRootAndMappedAncestor() throws Exception {
        Path actualRoot = temporary.resolve("actual-root");
        Files.createDirectory(actualRoot);
        Files.createDirectory(actualRoot.resolve("source"));
        Files.writeString(actualRoot.resolve("source/marker.txt"), "marker");
        try {
            Files.createSymbolicLink(temporary.resolve("linked-root"), actualRoot);
            assertThrows(ClosedDimensionCopyException.class, () -> new ClosedDimensionCopier().copy(
                    new ClosedDimensionCopyRequest(temporary.resolve("linked-root"), Path.of("source"), Path.of("destination"), "source", 1), success(1), fence()));

            Files.createDirectory(temporary.resolve("safe-root"));
            Files.createSymbolicLink(temporary.resolve("safe-root/linked-parent"), actualRoot);
            assertThrows(ClosedDimensionCopyException.class, () -> new ClosedDimensionCopier().copy(
                    new ClosedDimensionCopyRequest(temporary.resolve("safe-root"), Path.of("linked-parent/source"), Path.of("destination"), "source", 2), success(2), fence()));
        } catch (UnsupportedOperationException e) {
            return;
        }
    }

    @Test
    void retainsPartialDestinationOnInjectedFailureAndReleasesFence() throws Exception {
        sourceWithNestedMarker();
        AtomicBoolean released = new AtomicBoolean();
        ClosedDimensionCopier copier = new ClosedDimensionCopier(relative -> {
            if (relative.toString().equals("nested/marker.txt")) throw new IOException("injected");
        });
        assertThrows(ClosedDimensionCopyException.class, () -> copier.copy(request("source", "destination", 4), success(4),
                (key, generation) -> () -> released.set(true)));
        assertTrue(Files.isDirectory(temporary.resolve("destination")));
        assertTrue(released.get());
        assertEquals("marker", Files.readString(temporary.resolve("source/nested/marker.txt")));
    }

    @Test
    void rejectsAbsentWrongFailedAndDuplicateCloseReceipts() throws Exception {
        sourceWithNestedMarker();
        ClosedDimensionCopier copier = new ClosedDimensionCopier();
        assertThrows(ClosedDimensionCopyException.class, () -> copier.copy(request("source", "destination", 9), null, fence()));
        assertThrows(ClosedDimensionCopyException.class, () -> copier.copy(request("source", "destination", 9),
                new ClosedDimensionReceipt("wrong", 9, true, ClosedDimensionStatus.SAVED_AND_CLOSED, false), fence()));
        assertThrows(ClosedDimensionCopyException.class, () -> copier.copy(request("source", "destination", 9),
                new ClosedDimensionReceipt("source", 9, true, ClosedDimensionStatus.FAILED_CLOSE, true), fence()));

        copier.copy(request("source", "destination", 9), success(9), fence());
        assertThrows(ClosedDimensionCopyException.class, () -> copier.copy(request("source", "other-destination", 9), success(9), fence()));
    }

    private Path sourceWithNestedMarker() throws IOException {
        Path source = temporary.resolve("source");
        Files.createDirectory(source);
        Files.createDirectory(source.resolve("nested"));
        Files.writeString(source.resolve("nested/marker.txt"), "marker");
        return source;
    }

    private ClosedDimensionCopyRequest request(String source, String destination, long generation) {
        return new ClosedDimensionCopyRequest(temporary, Path.of(source), Path.of(destination), "source", generation);
    }

    private static ClosedDimensionReceipt success(long generation) {
        return new ClosedDimensionReceipt("source", generation, true, ClosedDimensionStatus.SAVED_AND_CLOSED, false);
    }

    private static SourceWriteReloadFence fence() {
        return (key, generation) -> () -> { };
    }
}
