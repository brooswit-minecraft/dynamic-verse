package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Non-native contract tests for {@link ProviderSafeDeleteCrashFixture}'s
 * game-root/world-root binding, requested after a prior draft conflated
 * {@code LevelResource.ROOT} (the world save directory) with the game
 * directory the wrapper actually launches and writes its nonce/marker/
 * result under -- a mismatch that would have refused to start on the very
 * first native run. These exercise {@code gameRootFromWorldRoot} directly
 * against real temp directories, with no MinecraftServer and no native
 * launch at all.
 */
class ProviderSafeDeleteCrashFixtureRootBindingTest {
    private static final String EXPECTED_GAME_NAME = "m470-safe-delete-crash-disposable-20261104";

    @Test void theIntendedPairResolvesTheExactGameRoot() throws IOException {
        Path game = Files.createTempDirectory("crash-root-test-").resolve(EXPECTED_GAME_NAME);
        Path world = game.resolve("world");
        Files.createDirectories(world);

        Path resolved = ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world);

        assertEquals(game.toAbsolutePath().normalize(), resolved);
    }

    @Test void aWorldDirectoryNotNamedWorldIsRefused() throws IOException {
        Path game = Files.createTempDirectory("crash-root-test-").resolve(EXPECTED_GAME_NAME);
        Path notWorld = game.resolve("not-world");
        Files.createDirectories(notWorld);

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(notWorld));
    }

    @Test void aGameRootWithTheWrongNameIsRefused() throws IOException {
        Path game = Files.createTempDirectory("crash-root-test-").resolve("some-other-directory-name");
        Path world = game.resolve("world");
        Files.createDirectories(world);

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world));
    }

    @Test void aForeignWorldDirectoryElsewhereWithTheRightNamesIsStillAcceptedOnlyIfCanonicallyRelated() throws IOException {
        // A "world" directory that happens to sit under a directory that
        // happens to be named correctly, but is not actually that exact
        // canonical pair (e.g. retained from an unrelated prior run copied
        // alongside), must still resolve correctly as long as it really is
        // the direct parent/child pair -- this is the ordinary accepted case,
        // included to make the boundary with the next (rejected) case clear.
        Path game = Files.createTempDirectory("crash-root-test-").resolve(EXPECTED_GAME_NAME);
        Path world = game.resolve("world");
        Files.createDirectories(world);
        assertEquals(game.toAbsolutePath().normalize(), ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world));
    }

    @Test void aWorldDirectoryThatIsNotTheGameRootsOwnChildIsRefused() throws IOException {
        Path base = Files.createTempDirectory("crash-root-test-");
        Path game = base.resolve(EXPECTED_GAME_NAME);
        Files.createDirectories(game);
        // A "world" directory that lives next to, not under, the expected
        // game root -- same basename contract satisfied at each level in
        // isolation, but not the real parent/child relationship.
        Path foreignWorld = base.resolve("world");
        Files.createDirectories(foreignWorld);

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(foreignWorld));
    }

    @Test void aSymlinkedWorldDirectoryIsRefused() throws IOException {
        Path base = Files.createTempDirectory("crash-root-test-");
        Path game = base.resolve(EXPECTED_GAME_NAME);
        Path realWorld = base.resolve("real-world");
        Files.createDirectories(game);
        Files.createDirectories(realWorld);
        Path linkedWorld = game.resolve("world");
        try {
            Files.createSymbolicLink(linkedWorld, realWorld);
        } catch (UnsupportedOperationException | IOException noSymlinkSupport) {
            return; // environment cannot create symlinks; nothing to assert here.
        }

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(linkedWorld));
    }

    @Test void aSymlinkedGameRootAncestorIsRefused() throws IOException {
        Path base = Files.createTempDirectory("crash-root-test-");
        Path realGame = base.resolve("real-" + EXPECTED_GAME_NAME);
        Files.createDirectories(realGame.resolve("world"));
        Path linkedGame = base.resolve(EXPECTED_GAME_NAME);
        try {
            Files.createSymbolicLink(linkedGame, realGame);
        } catch (UnsupportedOperationException | IOException noSymlinkSupport) {
            return;
        }
        Path world = linkedGame.resolve("world");

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world));
    }

    @Test void aRetainedForeignWorldMissingEntirelyIsRefused() {
        Path neverCreated = Path.of(System.getProperty("java.io.tmpdir"), "crash-root-test-never-created-" + System.nanoTime(), EXPECTED_GAME_NAME, "world");

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(neverCreated));
    }
}
