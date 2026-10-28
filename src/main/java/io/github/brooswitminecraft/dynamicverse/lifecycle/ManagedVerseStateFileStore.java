package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Small confined file store; it fails closed rather than falling back to a non-atomic replacement. */
public final class ManagedVerseStateFileStore implements ManagedVerseStateStore {
    private final Path file;

    public ManagedVerseStateFileStore(Path file) { this.file = file.toAbsolutePath().normalize(); }

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
                if (v.length != 5) throw new IllegalStateException("Malformed ready managed state");
                ready.put(key.substring("ready.".length()), new ReadyVerse(key.substring("ready.".length()),
                        VerseLifecycleCommandService.CreateType.valueOf(v[0]), Long.parseLong(v[1]), Long.parseLong(v[2]), v[3], v[4]));
            } else if (key.startsWith("operation.")) {
                String[] v = properties.getProperty(key).split("\\|", -1);
                if (v.length != 10) throw new IllegalStateException("Malformed operation managed state");
                UUID id = UUID.fromString(key.substring("operation.".length()));
                ReadyVerse descriptor = new ReadyVerse(v[5], VerseLifecycleCommandService.CreateType.valueOf(v[6]), Long.parseLong(v[7]), Long.parseLong(v[8]), v[9], "DEFAULT_DYNAMIC_PROPERTIES");
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
                Long.toString(value.effectiveSeed()), Long.toString(value.generation()), value.typeProvenance(), value.propertiesProvenance())));
        state.operations().values().forEach(value -> {
            ReadyVerse d = value.destinationDescriptor();
            properties.setProperty("operation." + value.id(), String.join("|", value.kind().name(), nullToEmpty(value.source()), value.destination(),
                    Boolean.toString(value.priorSourceLoaded()), value.phase().name(), d.id(), d.generator().name(), Long.toString(d.effectiveSeed()),
                    Long.toString(d.generation()), d.typeProvenance()));
        });
        Path temporary = parent.resolve(file.getFileName() + ".tmp");
        try {
            if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Managed-state temporary file already exists");
            try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { properties.store(out, "Dynamic Verse managed state"); }
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IllegalStateException("Managed-state atomic replacement unavailable", unsupported); }
        } catch (IOException bad) { throw new IllegalStateException("Cannot write managed state", bad); }
    }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }
}
