package io.github.brooswitminecraft.dynamicverse.lifecycle.delete;

import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionReceipt;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.SourceWriteReloadFence;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Descriptor-bound removal: exact custom path, NOFOLLOW handles, reversible claim before unlink. */
public final class ConfinedDimensionDeleter implements SafeDeleteOperation.Removal {
    interface Observer { void claimed(Path path) throws IOException; }
    private final Observer observer;
    public ConfinedDimensionDeleter() { this(path -> { }); }
    ConfinedDimensionDeleter(Observer observer) { this.observer=Objects.requireNonNull(observer); }
    @Override public void validate(SafeDeleteOperation.Target target) throws IOException {
        try (Handles handles=open(target)) {
            try (SecureDirectoryStream<Path> source=handles.owner.newDirectoryStream(target.relative().getFileName(),LinkOption.NOFOLLOW_LINKS)) {
                inventory(source,Path.of(""),new HashMap<>());
            }
        }
    }
    @Override public void delete(SafeDeleteOperation.Target target, ClosedDimensionReceipt receipt, SourceWriteReloadFence fence) throws Exception {
        if (receipt==null || !receipt.isExactSuccess(target.key(),receipt.generation())) throw new IOException("missing exact confirmed close");
        try (SourceWriteReloadFence.Lease retained=fence.retain(target.key(),receipt.generation())) {
            if (retained==null) throw new IOException("delete fence was not retained");
            try (Handles handles=open(target)) {
                Path name=target.relative().getFileName();
                var before=attributes(handles.owner,name);
                Map<Path,Stamp> expected=new HashMap<>();
                try (SecureDirectoryStream<Path> source=handles.owner.newDirectoryStream(name,LinkOption.NOFOLLOW_LINKS)) {
                    requireSame(before,source.getFileAttributeView(BasicFileAttributeView.class).readAttributes());
                    inventory(source,Path.of(""),expected);
                }
                Path claim=Path.of(target.quarantineName());
                // SecureDirectoryStream.move is relative to the same opened, confined parent. Never replace a retained claim.
                if (Files.exists(target.root().resolve(target.relative()).getParent().resolve(claim),LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("retained delete claim exists; automatic retry prohibited");
                handles.owner.move(name,handles.owner,claim);
                var claimed=attributes(handles.owner,claim);
                requireSame(before,claimed);
                observer.claimed(target.root().resolve(target.relative()).getParent().resolve(claim));
                try (SecureDirectoryStream<Path> source=handles.owner.newDirectoryStream(claim,LinkOption.NOFOLLOW_LINKS)) {
                    requireSame(claimed,source.getFileAttributeView(BasicFileAttributeView.class).readAttributes());
                    Map<Path,Stamp> actual=new HashMap<>();
                    inventory(source,Path.of(""),actual);
                    if (!expected.equals(actual)) throw new IOException("delete subtree changed during claim");
                }
                try (SecureDirectoryStream<Path> source=handles.owner.newDirectoryStream(claim,LinkOption.NOFOLLOW_LINKS)) {
                    requireSame(claimed,source.getFileAttributeView(BasicFileAttributeView.class).readAttributes());
                    removeContents(source,Path.of(""),expected);
                }
                requireSame(claimed,attributes(handles.owner,claim));
                handles.owner.deleteDirectory(claim);
            }
        }
    }
    private static Handles open(SafeDeleteOperation.Target target) throws IOException {
        Path root=target.root();
        if (!root.isAbsolute() || !root.equals(root.normalize())) throw new IOException("noncanonical storage root");
        Path walk=root.getRoot();
        for (Path element:root) {
            walk=walk.resolve(element);
            if (Files.isSymbolicLink(walk)) throw new IOException("linked storage ancestor");
        }
        var before=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        requireDirectory(before);
        DirectoryStream<Path> opened=Files.newDirectoryStream(root);
        if (!(opened instanceof SecureDirectoryStream<Path> secure)) { opened.close(); throw new IOException("SecureDirectoryStream unavailable"); }
        SecureDirectoryStream<Path> dimensions=null, owner=null;
        try {
            requireSame(before,secure.getFileAttributeView(BasicFileAttributeView.class).readAttributes());
            requireDirectory(attributes(secure,Path.of("dimensions")));
            dimensions=secure.newDirectoryStream(Path.of("dimensions"),LinkOption.NOFOLLOW_LINKS);
            requireDirectory(attributes(dimensions,Path.of("dynamicverse")));
            owner=dimensions.newDirectoryStream(Path.of("dynamicverse"),LinkOption.NOFOLLOW_LINKS);
            requireDirectory(attributes(owner,target.relative().getFileName()));
            return new Handles(secure,dimensions,owner);
        } catch (IOException | RuntimeException failure) {
            if (owner!=null) owner.close(); if (dimensions!=null) dimensions.close(); secure.close(); throw failure;
        }
    }
    private static void inventory(SecureDirectoryStream<Path> directory,Path relative,Map<Path,Stamp> out) throws IOException {
        for (Path entry:directory) {
            Path name=entry.getFileName();
            var attributes=attributes(directory,name);
            rejectSpecial(attributes);
            Path child=relative.resolve(name);
            out.put(child,Stamp.of(attributes));
            if (attributes.isDirectory()) {
                try (var nested=directory.newDirectoryStream(name,LinkOption.NOFOLLOW_LINKS)) {
                    requireSame(attributes,nested.getFileAttributeView(BasicFileAttributeView.class).readAttributes());
                    inventory(nested,child,out);
                }
            }
        }
    }
    private static void removeContents(SecureDirectoryStream<Path> directory,Path relative,Map<Path,Stamp> expected) throws IOException {
        for (Path entry:directory) {
            Path name=entry.getFileName(); Path child=relative.resolve(name);
            var attributes=attributes(directory,name); rejectSpecial(attributes);
            if (!Stamp.of(attributes).equals(expected.get(child))) throw new IOException("delete entry changed: "+child);
            if (attributes.isDirectory()) {
                try (var nested=directory.newDirectoryStream(name,LinkOption.NOFOLLOW_LINKS)) {
                    requireSame(attributes,nested.getFileAttributeView(BasicFileAttributeView.class).readAttributes());
                    removeContents(nested,child,expected);
                }
                requireSame(attributes,attributes(directory,name)); directory.deleteDirectory(name);
            } else directory.deleteFile(name); // relative unlink never follows a link to an external target
        }
    }
    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory,Path name) throws IOException {
        if (name==null || name.isAbsolute() || name.getNameCount()!=1) throw new IOException("unsafe entry");
        return directory.getFileAttributeView(name,BasicFileAttributeView.class,LinkOption.NOFOLLOW_LINKS).readAttributes();
    }
    private static void requireDirectory(BasicFileAttributes a) throws IOException { rejectSpecial(a); if (!a.isDirectory()) throw new IOException("expected directory"); }
    private static void rejectSpecial(BasicFileAttributes a) throws IOException {
        if (a.fileKey()==null || a.isSymbolicLink() || (!a.isDirectory() && !a.isRegularFile())) throw new IOException("linked, special or unidentified file");
    }
    private static void requireSame(BasicFileAttributes a,BasicFileAttributes b) throws IOException {
        if (a.fileKey()==null || !a.fileKey().equals(b.fileKey())) throw new IOException("directory identity changed");
    }
    private record Stamp(Object key,boolean directory,long size,long modified) {
        static Stamp of(BasicFileAttributes a) { return new Stamp(a.fileKey(),a.isDirectory(),a.size(),a.lastModifiedTime().toMillis()); }
    }
    private record Handles(SecureDirectoryStream<Path> root,SecureDirectoryStream<Path> dimensions,SecureDirectoryStream<Path> owner) implements AutoCloseable {
        @Override public void close() throws IOException { try (root; dimensions; owner) { } }
    }
}
