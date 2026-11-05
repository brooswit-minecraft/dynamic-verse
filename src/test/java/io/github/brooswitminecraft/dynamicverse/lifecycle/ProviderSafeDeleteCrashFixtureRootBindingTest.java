package io.github.brooswitminecraft.dynamicverse.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
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
 *
 * <p>{@code gameRootFromWorldRoot} takes the expected game root as an
 * explicit parameter (mirroring the launch contract supplying it via a
 * system property), and requires an EXACT match rather than merely a
 * plausible basename/shape -- a same-named, same-shaped pair located
 * somewhere else on disk must still be refused.</p>
 */
class ProviderSafeDeleteCrashFixtureRootBindingTest {
    private static final String EXPECTED_GAME_NAME = "m470-safe-delete-crash-disposable-20261104";

    @Test void theIntendedPairResolvesTheExactGameRoot() throws IOException {
        Path game = Files.createTempDirectory("crash-root-test-").resolve(EXPECTED_GAME_NAME);
        Path world = game.resolve("world");
        Files.createDirectories(world);

        Path resolved = ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world, game);

        assertEquals(game.toAbsolutePath().normalize(), resolved);
    }

    @Test void aWorldDirectoryNotNamedWorldIsRefused() throws IOException {
        Path game = Files.createTempDirectory("crash-root-test-").resolve(EXPECTED_GAME_NAME);
        Path notWorld = game.resolve("not-world");
        Files.createDirectories(notWorld);

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(notWorld, game));
    }

    @Test void aGameRootWithTheWrongNameIsRefused() throws IOException {
        Path expected = Files.createTempDirectory("crash-root-test-").resolve(EXPECTED_GAME_NAME);
        Path actualGame = expected.getParent().resolve("some-other-directory-name");
        Path world = actualGame.resolve("world");
        Files.createDirectories(world);

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world, expected));
    }

    @Test void aForeignPairElsewhereWithTheRightNamesAndShapeIsStillRefusedUnlessItIsTheExactExpectedRoot() throws IOException {
        // A same-named, same-shaped "world" under a correctly-named parent
        // -- but retained/copied somewhere other than the one true,
        // launch-contract-registered root -- must be refused. Confinement
        // means the exact root, not any directory that merely looks right.
        Path base = Files.createTempDirectory("crash-root-test-");
        Path foreignGame = base.resolve("foreign").resolve(EXPECTED_GAME_NAME);
        Path foreignWorld = foreignGame.resolve("world");
        Files.createDirectories(foreignWorld);
        Path trueExpectedGame = base.resolve("true").resolve(EXPECTED_GAME_NAME);

        assertThrows(IllegalStateException.class,
                () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(foreignWorld, trueExpectedGame));
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

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(foreignWorld, game));
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
            Assumptions.abort("this environment cannot create symlinks; skipping rather than silently passing");
            return;
        }

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(linkedWorld, game));
    }

    @Test void aSymlinkedGameRootAncestorIsRefused() throws IOException {
        Path base = Files.createTempDirectory("crash-root-test-");
        Path realGame = base.resolve("real-" + EXPECTED_GAME_NAME);
        Files.createDirectories(realGame.resolve("world"));
        Path linkedGame = base.resolve(EXPECTED_GAME_NAME);
        try {
            Files.createSymbolicLink(linkedGame, realGame);
        } catch (UnsupportedOperationException | IOException noSymlinkSupport) {
            Assumptions.abort("this environment cannot create symlinks; skipping rather than silently passing");
            return;
        }
        Path world = linkedGame.resolve("world");

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(world, linkedGame));
    }

    @Test void aRetainedForeignWorldMissingEntirelyIsRefused() {
        Path neverCreated = Path.of(System.getProperty("java.io.tmpdir"), "crash-root-test-never-created-" + System.nanoTime(), EXPECTED_GAME_NAME, "world");
        Path expected = neverCreated.getParent();

        assertThrows(IllegalStateException.class, () -> ProviderSafeDeleteCrashFixture.gameRootFromWorldRoot(neverCreated, expected));
    }
}
