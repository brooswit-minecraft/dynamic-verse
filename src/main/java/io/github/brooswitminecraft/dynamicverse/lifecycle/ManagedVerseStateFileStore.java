package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Small confined file store; it fails closed rather than falling back to a non-atomic replacement. */
public final class ManagedVerseStateFileStore implements ManagedVerseStateStore {
    private final Path file;
    private final AtomicReplace atomicReplace;
    private final Supplier<String> stagingNames;

    public ManagedVerseStateFileStore(Path file) {
        this(file, ManagedVerseStateFileStore::atomicReplace, () -> UUID.randomUUID().toString());
    }

    /** Package-visible only so the same write path can prove failure preservation locally. */
    ManagedVerseStateFileStore(Path file, AtomicReplace atomicReplace) {
        this(file, atomicReplace, () -> UUID.randomUUID().toString());
    }

    /** Package-visible only so the same CREATE_NEW allocation path can prove collision refusal locally. */
    ManagedVerseStateFileStore(Path file, Supplier<String> stagingNames) {
        this(file, ManagedVerseStateFileStore::atomicReplace, stagingNames);
    }

    private ManagedVerseStateFileStore(Path file, AtomicReplace atomicReplace, Supplier<String> stagingNames) {
        this.file = file.toAbsolutePath().normalize();
        this.atomicReplace = Objects.requireNonNull(atomicReplace);
        this.stagingNames = Objects.requireNonNull(stagingNames);
    }

    /** The server-owned root is the only production location; tests inject an in-memory or temp-file store. */
    public static ManagedVerseStateFileStore minecraft(MinecraftServer server) {
        return new ManagedVerseStateFileStore(server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("dynamicverse-managed-state.properties"));
    }

    @Override public synchronized State read() {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return State.empty();
        if (Files.isSymbolicLink(file)) throw new IllegalStateException("Managed-state file is linked");
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { properties.load(in); }
        catch (IOException bad) { throw new IllegalStateException("Cannot read managed state", bad); }
        Map<String, ReadyVerse> ready = new HashMap<>();
        Map<UUID, OperationIntent> operations = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("ready.")) {
                String[] v = properties.getProperty(key).split("\\|", -1);
                if (v.length != 5 && v.length != 6) throw new IllegalStateException("Malformed ready managed state");
                ready.put(key.substring("ready.".length()), new ReadyVerse(key.substring("ready.".length()),
                        VerseLifecycleCommandService.CreateType.valueOf(v[0]), v.length == 6 && !v[1].isEmpty() ? java.util.OptionalLong.of(Long.parseLong(v[1])) : java.util.OptionalLong.empty(),
                        Long.parseLong(v.length == 6 ? v[2] : v[1]), Long.parseLong(v.length == 6 ? v[3] : v[2]),
                        v.length == 6 ? v[4] : v[3], v.length == 6 ? v[5] : v[4]));
            } else if (key.startsWith("operation.")) {
                String[] v = properties.getProperty(key).split("\\|", -1);
                if (v.length != 10 && v.length != 11) throw new IllegalStateException("Malformed operation managed state");
                UUID id = UUID.fromString(key.substring("operation.".length()));
                ReadyVerse descriptor = new ReadyVerse(v[5], VerseLifecycleCommandService.CreateType.valueOf(v[6]),
                        v.length == 11 && !v[7].isEmpty() ? java.util.OptionalLong.of(Long.parseLong(v[7])) : java.util.OptionalLong.empty(),
                        Long.parseLong(v.length == 11 ? v[8] : v[7]), Long.parseLong(v.length == 11 ? v[9] : v[8]),
                        v.length == 11 ? v[10] : v[9], "DEFAULT_DYNAMIC_PROPERTIES");
                operations.put(id, new OperationIntent(id, OperationKind.valueOf(v[0]), emptyToNull(v[1]), v[2], Boolean.parseBoolean(v[3]), descriptor,
                        OperationPhase.valueOf(v[4]), "FAILED".equals(v[4]) ? "ERR_OPERATION_INTERRUPTED" : null));
            }
        }
        return new State(ready, operations);
    }

    @Override public synchronized void write(State state) {
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IllegalStateException("Managed-state parent is invalid");
        }
        Properties properties = new Properties();
        state.ready().values().forEach(value -> properties.setProperty("ready." + value.id(), String.join("|", value.generator().name(),
                value.requestedSeed().isPresent() ? Long.toString(value.requestedSeed().getAsLong()) : "", Long.toString(value.effectiveSeed()),
                Long.toString(value.generation()), value.typeProvenance(), value.propertiesProvenance())));
        state.operations().values().forEach(value -> {
            ReadyVerse d = value.destinationDescriptor();
            properties.setProperty("operation." + value.id(), String.join("|", value.kind().name(), nullToEmpty(value.source()), value.destination(),
                    Boolean.toString(value.priorSourceLoaded()), value.phase().name(), d.id(), d.generator().name(),
                    d.requestedSeed().isPresent() ? Long.toString(d.requestedSeed().getAsLong()) : "", Long.toString(d.effectiveSeed()),
                    Long.toString(d.generation()), d.typeProvenance()));
        });
        try {
            // A residue is uncommitted evidence, never a source of state.  A new
            // CREATE_NEW sibling is opened, serialized, and forced through its
            // one owned handle; no reopened path can follow a replacement link.
            StagingFile temporary = createStaging(parent);
            try (FileChannel channel = temporary.channel()) {
                OutputStream out = Channels.newOutputStream(channel);
                properties.store(out, "Dynamic Verse managed state");
                out.flush();
                channel.force(true);
            }
            try { atomicReplace.replace(temporary.path(), file); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IllegalStateException("Managed-state atomic replacement unavailable", unsupported); }
        } catch (IOException bad) { throw new IllegalStateException("Cannot write managed state", bad); }
    }
    private StagingFile createStaging(Path parent) throws IOException {
        for (int attempt = 0; attempt < 32; attempt++) {
            Path candidate = parent.resolve(file.getFileName() + ".staging-" + stagingNames.get() + ".tmp");
            try {
                return new StagingFile(candidate, FileChannel.open(candidate, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
            } catch (FileAlreadyExistsException collision) {
                // Existing residue or a substituted entry is evidence, not ours.
            }
        }
        throw new IOException("Cannot allocate unique managed-state staging file");
    }
    private static void atomicReplace(Path staging, Path committed) throws IOException {
        Files.move(staging, committed, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    @FunctionalInterface interface AtomicReplace { void replace(Path staging, Path committed) throws IOException; }
    private record StagingFile(Path path, FileChannel channel) { }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }
}
