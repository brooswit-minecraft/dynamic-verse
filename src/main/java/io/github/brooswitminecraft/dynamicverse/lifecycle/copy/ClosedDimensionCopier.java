package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Production closed-dimension copier. It accepts only a provider-resolved,
 * fenced successful close receipt and copies one exact dimension directory.
 * It never deletes, replaces, loads, or retries a destination.
 */
public final class ClosedDimensionCopier {
    @FunctionalInterface
    interface CopyObserver { void beforeEntry(Path relative) throws IOException; }

    private static final CopyObserver NO_OBSERVER = ignored -> { };
    private final CopyObserver observer;
    private final Set<ReceiptId> consumedReceipts = new HashSet<>();

    public ClosedDimensionCopier() { this(NO_OBSERVER); }

    ClosedDimensionCopier(CopyObserver observer) { this.observer = Objects.requireNonNull(observer); }

    public synchronized void copy(ClosedDimensionCopyRequest request, ClosedDimensionReceipt receipt,
                                  SourceWriteReloadFence fence) throws ClosedDimensionCopyException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(fence);
        if (receipt == null || !receipt.isExactSuccess(request.sourceKey(), request.generation())) {
            throw new ClosedDimensionCopyException("Missing, wrong, or failed SAVED_AND_CLOSED receipt");
        }
        ReceiptId receiptId = new ReceiptId(receipt.sourceKey(), receipt.generation());
        if (!consumedReceipts.add(receiptId)) {
            throw new ClosedDimensionCopyException("Close generation was already consumed; automatic retry is prohibited");
        }
        SourceWriteReloadFence.Lease lease;
        try {
            lease = fence.retain(request.sourceKey(), request.generation());
        } catch (RuntimeException e) {
            throw new ClosedDimensionCopyException("Copy fence refused retention", e);
        }
        if (lease == null) throw new ClosedDimensionCopyException("Copy fence returned no retained lease");
        try (lease) {
            this.copyFenced(request);
        } catch (ClosedDimensionCopyException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ClosedDimensionCopyException("Copy fence failed", e);
        }
    }

    private void copyFenced(ClosedDimensionCopyRequest request) throws ClosedDimensionCopyException {
        try {
            Path root = checkedRoot(request.storageRoot());
            Path sourceRelative = checkedRelative(request.sourceRelative());
            Path destinationRelative = checkedRelative(request.destinationRelative());
            if (sourceRelative.equals(destinationRelative) || sourceRelative.startsWith(destinationRelative)
                    || destinationRelative.startsWith(sourceRelative)) {
                throw new ClosedDimensionCopyException("Source and destination overlap");
            }
            Path source = root.resolve(sourceRelative).normalize();
            Path destination = root.resolve(destinationRelative).normalize();
            if (!source.startsWith(root) || !destination.startsWith(root)) throw new ClosedDimensionCopyException("Storage mapping escaped root");
            rejectLinkedAncestors(root, source.getParent());
            rejectLinkedAncestors(root, destination.getParent());
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new ClosedDimensionCopyException("Source is not an exact directory");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new ClosedDimensionCopyException("Destination already exists");

            DirectoryStream<Path> rootStream = Files.newDirectoryStream(root);
            if (!(rootStream instanceof SecureDirectoryStream<Path>)) {
                rootStream.close();
                throw new ClosedDimensionCopyException("Filesystem lacks SecureDirectoryStream; path-substitution guarantees unavailable");
            }
            try (SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) rootStream) {
                try {
                    Files.createDirectory(destination); // CREATE_NEW directory claim; never replacement.
                } catch (FileAlreadyExistsException e) {
                    throw new ClosedDimensionCopyException("Destination claim lost", e);
                }
                rejectLinkedAncestors(root, destination);
                ParentHandle<SecureDirectoryStream<Path>> sourceParent = openParent(secureRoot, sourceRelative);
                try {
                    ParentHandle<SecureDirectoryStream<Path>> destinationParent = openParent(secureRoot, destinationRelative);
                    try {
                        copyDirectory(sourceParent.stream(), sourceRelative.getFileName(), destinationParent.stream(), destinationRelative.getFileName(),
                                destination, Path.of(""));
                    } finally {
                        destinationParent.close();
                    }
                } finally {
                    sourceParent.close();
                }
            }
        } catch (ClosedDimensionCopyException e) {
            throw e;
        } catch (IOException e) {
            throw new ClosedDimensionCopyException("Copy failed; partial destination remains unavailable", e);
        }
    }

    private void copyDirectory(SecureDirectoryStream<Path> sourceParent, Path sourceName,
                               SecureDirectoryStream<Path> destinationParent, Path destinationName,
                               Path destinationFull, Path relative) throws IOException, ClosedDimensionCopyException {
        BasicFileAttributes sourceAttributes = attributes(sourceParent, sourceName);
        requireDirectory(sourceAttributes, relative);
        try (SecureDirectoryStream<Path> source = sourceParent.newDirectoryStream(sourceName, LinkOption.NOFOLLOW_LINKS);
             SecureDirectoryStream<Path> destination = destinationParent.newDirectoryStream(destinationName, LinkOption.NOFOLLOW_LINKS)) {
            for (Path child : source) {
                Path name = child.getFileName();
                if (name == null || name.getNameCount() != 1) throw new ClosedDimensionCopyException("Unsafe directory entry");
                Path childRelative = relative.resolve(name);
                BasicFileAttributes attributes = attributes(source, name);
                rejectLinkOrSpecial(attributes, childRelative);
                observer.beforeEntry(childRelative);
                if (attributes.isDirectory()) {
                    // The destination stream is authoritative for all file I/O; mkdir is CREATE_NEW then reopened NOFOLLOW.
                    Path childDestination = destinationFull.resolve(name).normalize();
                    Files.createDirectory(childDestination);
                    copyDirectory(source, name, destination, name, childDestination, childRelative);
                } else if (attributes.isRegularFile()) {
                    copyFile(source, name, destination, name, childRelative);
                } else {
                    throw new ClosedDimensionCopyException("Special file rejected: " + childRelative);
                }
            }
        }
    }

    private void copyFile(SecureDirectoryStream<Path> source, Path sourceName,
                          SecureDirectoryStream<Path> destination, Path destinationName,
                          Path relative) throws IOException, ClosedDimensionCopyException {
        try (SeekableByteChannel in = source.newByteChannel(sourceName, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             SeekableByteChannel out = destination.newByteChannel(destinationName,
                     Set.<OpenOption>of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (in.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) out.write(buffer);
                buffer.clear();
            }
        } catch (FileAlreadyExistsException e) {
            throw new ClosedDimensionCopyException("Destination entry already exists: " + relative, e);
        }
    }

    private static ParentHandle<SecureDirectoryStream<Path>> openParent(SecureDirectoryStream<Path> root, Path relative) throws IOException, ClosedDimensionCopyException {
        return openParent(root, relative, new DirectoryStreamOperations<>() {
            @Override public BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path entry) throws IOException {
                return ClosedDimensionCopier.attributes(directory, entry);
            }
            @Override public SecureDirectoryStream<Path> open(SecureDirectoryStream<Path> directory, Path entry) throws IOException {
                return directory.newDirectoryStream(entry, LinkOption.NOFOLLOW_LINKS);
            }
            @Override public void close(SecureDirectoryStream<Path> directory) throws IOException { directory.close(); }
        });
    }

    static <S> ParentHandle<S> openParent(S root, Path relative, DirectoryStreamOperations<S> operations) throws IOException, ClosedDimensionCopyException {
        S current = root;
        boolean ownsCurrent = false;
        Path parent = relative.getParent();
        if (parent == null) return new ParentHandle<>(root, false);
        try {
            for (Path name : parent) {
                BasicFileAttributes attrs = operations.attributes(current, name);
                requireDirectory(attrs, name);
                S next = operations.open(current, name);
                if (ownsCurrent) {
                    try {
                        operations.close(current);
                    } catch (IOException handoffFailure) {
                        try {
                            operations.close(next);
                        } catch (IOException cleanupFailure) {
                            handoffFailure.addSuppressed(cleanupFailure);
                        }
                        throw handoffFailure;
                    }
                }
                current = next;
                ownsCurrent = true;
            }
            return new ParentHandle<>(current, ownsCurrent);
        } catch (IOException | ClosedDimensionCopyException e) {
            if (ownsCurrent) {
                try {
                    operations.close(current);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path entry) throws IOException {
        return directory.getFileAttributeView(entry, java.nio.file.attribute.BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
    }

    private static void requireDirectory(BasicFileAttributes attributes, Path entry) throws ClosedDimensionCopyException {
        rejectLinkOrSpecial(attributes, entry);
        if (!attributes.isDirectory()) throw new ClosedDimensionCopyException("Expected directory: " + entry);
    }

    private static void rejectLinkOrSpecial(BasicFileAttributes attributes, Path entry) throws ClosedDimensionCopyException {
        if (attributes.isSymbolicLink() || attributes.isOther()) throw new ClosedDimensionCopyException("Link or special file rejected: " + entry);
    }

    private static Path checkedRoot(Path root) throws IOException, ClosedDimensionCopyException {
        Path absolute = root.toAbsolutePath().normalize();
        rejectLinkedAncestors(absolute.getRoot(), absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new ClosedDimensionCopyException("Storage root is not a directory");
        return absolute;
    }

    private static Path checkedRelative(Path relative) throws ClosedDimensionCopyException {
        if (relative.toString().isEmpty() || relative.isAbsolute() || relative.getNameCount() == 0 || !relative.normalize().equals(relative)) {
            throw new ClosedDimensionCopyException("Storage mapping must be a normalized relative path");
        }
        for (Path name : relative) if (name.toString().equals(".") || name.toString().equals("..")) {
            throw new ClosedDimensionCopyException("Traversal in storage mapping");
        }
        return relative;
    }

    private static void rejectLinkedAncestors(Path root, Path candidate) throws IOException, ClosedDimensionCopyException {
        Path current = candidate.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isSymbolicLink(current)) throw new ClosedDimensionCopyException("Linked root or ancestor rejected: " + current);
            if (root != null && current.equals(root.toAbsolutePath().normalize())) return;
            current = current.getParent();
        }
    }

    private record ReceiptId(String key, long generation) { }

    interface DirectoryStreamOperations<S> {
        BasicFileAttributes attributes(S directory, Path entry) throws IOException;
        S open(S directory, Path entry) throws IOException;
        void close(S directory) throws IOException;
    }

    static final class ParentHandle<S> implements AutoCloseable {
        private final S stream;
        private final boolean owned;
        ParentHandle(S stream, boolean owned) { this.stream = stream; this.owned = owned; }
        S stream() { return stream; }
        @Override public void close() throws IOException {
            if (owned && stream instanceof SecureDirectoryStream<?> directory) directory.close();
        }
    }
}
