package io.github.brooswitminecraft.dynamicverse.travel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseCompletionSink;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLeaseFixtures;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationId;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Platform-boundary coverage prepared for the NeoForge GameTest runner.
 * It deliberately uses the production registrar and Minecraft argument types.
 */
@GameTestHolder("dynamicverse")
@PrefixGameTestTemplate(false)
public final class NeoForgeVerseCommandsGameTest {
    private NeoForgeVerseCommandsGameTest() { }

    // This exact pinned-Minecraft template exists at data/minecraft/structure/bastion/mobs/empty.nbt.
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void registeredCommandBoundary(GameTestHelper helper) throws Exception {
        ServerPlayer[] ownedPlayers = new ServerPlayer[2];
        AtomicBoolean cleanedUp = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (cleanedUp.compareAndSet(false, true)) {
                for (ServerPlayer player : ownedPlayers) {
                    if (player != null) helper.getLevel().getServer().getPlayerList().remove(player);
                }
            }
        };
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) { }
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo, GameTestRunner runner) { cleanup.run(); }
        });
        boolean[] finishScheduled = {false};
        try {
            ServerPlayer target = registerMockServerPlayer(helper, "dvtarget1", ownedPlayers, 0);
            String targetName = target.getGameProfile().getName();
            ServerPlayer caller = registerMockServerPlayer(helper, "dvcaller1", ownedPlayers, 1);
            String callerName = caller.getGameProfile().getName();
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(target.getUUID()) == target, "test-owned target must be online by UUID");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayerByName(targetName) == target, "test-owned target must be the single named player");
            helper.assertTrue(!target.getUUID().equals(caller.getUUID()) && !targetName.equals(callerName), "bring caller and target must have distinct UUIDs and names");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(caller.getUUID()) == caller, "bring caller must be online by UUID");
            helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayerByName(callerName) == caller, "bring caller must be online by name");
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            PendingAccess lifecycle = new PendingAccess();
            RecordingLevelResolver levels = new RecordingLevelResolver(helper);
            NeoForgeVerseCommands.register(dispatcher, lifecycle, levels);

            RecordingSource feedback = new RecordingSource();
            CommandSourceStack playerOperator = source(feedback, target, helper, 2);
            CommandSourceStack commandBlockOperator = source(feedback, null, helper, 2);
            CommandSourceStack denied = source(feedback, target, helper, 1);

            // Actual root permission predicate and actual EntityArgument selector resolution.
            expectRejected(() -> dispatcher.execute("verse send " + targetName + " moon", denied),
                    CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(), "permission denial");
            helper.assertTrue(lifecycle.admissions == 0, "denied command must not acquire lifecycle access");
            Vec3 beforeRejectedRoutes = target.position();
            expectRejected(() -> dispatcher.execute("verse tp moon", commandBlockOperator),
                    CommandSourceStack.ERROR_NOT_PLAYER, "command-block tp player requirement");
            expectRejected(() -> dispatcher.execute("verse bring " + targetName, commandBlockOperator),
                    CommandSourceStack.ERROR_NOT_PLAYER, "command-block bring player requirement");
            helper.assertTrue(lifecycle.admissions == 0, "rejected routes must not acquire lifecycle access");
            assertPosition(helper, target, beforeRejectedRoutes, "rejected routes must not move the target");

            // Existing service tests cover these outcomes; exercise the actual registrar/parser boundary here.
            Vec3 targetBeforePlatformRejections = target.position();
            Vec3 callerBeforePlatformRejections = caller.position();
            feedback.clear();
            expectRejected(() -> dispatcher.execute("verse send absentplayer moon", source(feedback, caller, helper, 2)),
                    EntityArgument.NO_PLAYERS_FOUND, "absent player");
            helper.assertTrue(lifecycle.admissions == 0, "absent player must not acquire lifecycle access");
            helper.assertTrue(feedback.successes.isEmpty(), "absent player must not emit command feedback");
            assertPosition(helper, target, targetBeforePlatformRejections, "absent player must not move target");
            assertPosition(helper, caller, callerBeforePlatformRejections, "absent player must not move caller");

            feedback.clear();
            dispatcher.execute("verse send " + targetName + " moon 30000000 64 0", source(feedback, caller, helper, 2));
            helper.assertTrue(lifecycle.admissions == 0, "invalid coordinates must not acquire lifecycle access");
            helper.assertTrue(feedback.successes.equals(List.of("travel failed: INVALID_COORDINATES")),
                    "invalid coordinates must emit only INVALID_COORDINATES failure, never accepted/moved");
            assertPosition(helper, target, targetBeforePlatformRejections, "invalid coordinates must not move target");
            assertPosition(helper, caller, callerBeforePlatformRejections, "invalid coordinates must not move caller");

            // The actual registered handler emits pending first; callback completion invokes the real ServerPlayer adapter.
            // Vec3Argument.vec3 centers nonrelative integer X/Z on the addressed blocks.
            feedback.clear();
            dispatcher.execute("verse send @s moon 1 64 2", playerOperator);
            helper.assertTrue(feedback.successes.stream().anyMatch(message -> message.startsWith("travel accepted/pending")), "permission-2 player source should execute actual /verse send");
            completeAndAssertMoved(helper, lifecycle, levels, feedback, target,
                    new Vec3(1.5, 64, 2.5), "absolute integer-centered");

            // Vec3Argument resolves relative coordinates from the real CommandSourceStack position.
            feedback.clear();
            dispatcher.execute("verse send @s moon ~1 ~ ~-2", playerOperator);
            helper.assertTrue(feedback.successes.stream().anyMatch(message -> message.startsWith("travel accepted/pending")), "relative Vec3 should reach production handler");
            completeAndAssertMoved(helper, lifecycle, levels, feedback, target,
                    playerOperator.getPosition().add(1, 0, -2), "relative");

            // Null requested position is the production default/spawn path; command-block send remains allowed.
            feedback.clear();
            dispatcher.execute("verse send " + targetName + " moon", commandBlockOperator);
            helper.assertTrue(feedback.successes.stream().anyMatch(message -> message.startsWith("travel accepted/pending")), "command-block source should execute /verse send to the verified single player");
            completeAndAssertMoved(helper, lifecycle, levels, feedback, target,
                    new Vec3(helper.getLevel().getSharedSpawnPos().getX(), helper.getLevel().getSharedSpawnPos().getY(), helper.getLevel().getSharedSpawnPos().getZ()),
                    "default spawn");

            // Successful player-only /verse tp uses the same production registrar and lease-backed adapter path.
            feedback.clear();
            CommandSourceStack targetOperator = source(feedback, target, helper, 2);
            dispatcher.execute("verse tp moon -3 72 4", targetOperator);
            completeAndAssertMoved(helper, lifecycle, levels, feedback, target,
                    new Vec3(-2.5, 72, 4.5), "player-only tp integer-centered");

            // Use separately named/UUID-backed players to prove /verse send routes only the named target.
            caller.setPos(8.25, 71, -4.75);
            caller.setDeltaMovement(Vec3.ZERO);
            caller.setNoGravity(true);
            target.setDeltaMovement(Vec3.ZERO);
            target.setNoGravity(true);
            Vec3 callerBeforeSend = caller.position();
            Vec3 targetBeforeSend = target.position();
            Vec3 explicitSendDestination = new Vec3(5.5, 70, 6.5);
            helper.assertTrue(targetBeforeSend.distanceToSqr(callerBeforeSend) > 0.000001D,
                    "send must begin with distinct caller/target positions");
            helper.assertTrue(explicitSendDestination.distanceToSqr(targetBeforeSend) > 0.000001D
                            && explicitSendDestination.distanceToSqr(callerBeforeSend) > 0.000001D,
                    "send destination must differ from both starting positions");
            feedback.clear();
            dispatcher.execute("verse send " + targetName + " moon 5 70 6", source(feedback, caller, helper, 2));
            completeAndAssertMoved(helper, lifecycle, levels, feedback, target, explicitSendDestination,
                    "distinct-target send integer-centered");
            assertPosition(helper, caller, callerBeforeSend, "send caller must retain identity and position");

            // The same distinct players prove /verse bring moves only the named target to the caller.
            Vec3 callerBeforeBring = caller.position();
            Vec3 targetBeforeBring = target.position();
            helper.assertTrue(targetBeforeBring.distanceToSqr(callerBeforeBring) > 0.000001D, "bring must begin with distinct caller/target positions");
            feedback.clear();
            int admissionsBeforeBring = lifecycle.admissions;
            dispatcher.execute("verse bring " + targetName, source(feedback, caller, helper, 2));
            assertPendingBeforeOptionalTerminal(helper, feedback, "bring");
            helper.assertTrue(lifecycle.admissions == admissionsBeforeBring, "bring must not acquire a verse lease");
            helper.runAfterDelay(1, () -> {
                try {
                    helper.assertTrue(feedback.successes.equals(List.of("travel accepted/pending", "travel moved")), "bring must report exactly one terminal movement after pending");
                    assertPosition(helper, target, callerBeforeBring, "bring target destination");
                    assertPosition(helper, caller, callerBeforeBring, "bring caller must retain identity and position");
                    helper.assertTrue(lifecycle.admissions == admissionsBeforeBring, "bring completion must not acquire a verse lease");
                    helper.succeed();
                } finally {
                    cleanup.run();
                }
            });
            finishScheduled[0] = true;
        } finally {
            if (!finishScheduled[0]) {
                cleanup.run();
            }
        }
    }

    private static void assertPendingBeforeOptionalTerminal(GameTestHelper helper, RecordingSource feedback, String caseName) {
        helper.assertTrue(!feedback.successes.isEmpty() && feedback.successes.get(0).equals("travel accepted/pending"),
                caseName + " must report pending before any terminal movement");
        helper.assertTrue(feedback.successes.size() <= 2,
                caseName + " must not report duplicate terminal feedback");
        if (feedback.successes.size() == 2) {
            helper.assertTrue(feedback.successes.get(1).equals("travel moved"),
                    caseName + " inline completion must report exactly one moved terminal");
        }
    }

    /** Mirrors the pinned GameTestHelper mock registration flow with a unique caller profile. */
    private static ServerPlayer registerMockServerPlayer(GameTestHelper helper, String name,
                                                          ServerPlayer[] ownedPlayers, int ownedIndex) {
        GameProfile profile = new GameProfile(java.util.UUID.randomUUID(), name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new TestServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        // Publish ownership before placement: PlayerList registration may throw after a partial add.
        ownedPlayers[ownedIndex] = player;
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static CommandSourceStack source(RecordingSource source, ServerPlayer player, GameTestHelper helper, int permission) {
        Vec3 position = player == null ? Vec3.ZERO : player.position();
        return new CommandSourceStack(source, position, Vec2.ZERO, helper.getLevel(), permission,
                player == null ? "game-test-command-block" : player.getScoreboardName(), Component.literal("game-test"),
                helper.getLevel().getServer(), player);
    }

    private static void expectRejected(ThrowingRunnable command, CommandExceptionType expectedType, String caseName) {
        try {
            command.run();
            throw new AssertionError("command unexpectedly executed");
        } catch (CommandSyntaxException expected) {
            if (expected.getType() != expectedType) {
                throw new AssertionError(caseName + " rejected with " + expected.getType() + " rather than " + expectedType, expected);
            }
        }
    }

    @FunctionalInterface private interface ThrowingRunnable {
        void run() throws CommandSyntaxException;
    }

    private static void completeAndAssertMoved(
            GameTestHelper helper, PendingAccess lifecycle, RecordingLevelResolver levels, RecordingSource feedback,
            ServerPlayer target, Vec3 expected, String caseName) {
        PendingAccess.Request request = lifecycle.next();
        AtomicInteger releases = new AtomicInteger();
        ReadyVerseLease lease = ReadyVerseLeaseFixtures.acquired(
                "moon", "game-test-level", 17L, request.id(), releases::incrementAndGet);
        levels.expect(lease);
        request.callback().complete(VerseReservationSnapshot.acquired(request.id(), lease));
        helper.assertTrue(levels.resolvedLease == lease, caseName + " must resolve the exact acquired lease");
        helper.assertTrue(releases.get() == 1, caseName + " must release the exact lease once");
        helper.assertTrue(feedback.successes.size() == 2
                        && feedback.successes.get(0).startsWith("travel accepted/pending")
                        && feedback.successes.get(1).equals("travel moved"),
                caseName + " must report pending before exactly one terminal movement");
        assertPosition(helper, target, expected, caseName + " destination");
    }

    private static void assertPosition(GameTestHelper helper, ServerPlayer player, Vec3 expected, String message) {
        helper.assertTrue(helper.getLevel().getServer().getPlayerList().getPlayer(player.getUUID()) == player,
                message + " must retain the exact target identity");
        helper.assertTrue(player.serverLevel() == helper.getLevel(), message + " must stay in the resolved level");
        helper.assertTrue(player.position().distanceToSqr(expected) < 0.000001D,
                message + ": expected " + expected + " but was " + player.position());
    }

    private static final class PendingAccess implements VerseAccessService {
        int admissions;
        private final ArrayDeque<Request> requests = new ArrayDeque<>();
        @Override public VerseReservationSnapshot beginAcquireReadyRead(String verseName, VerseCompletionSink<VerseReservationSnapshot> callback) {
            admissions++;
            VerseReservationId id = VerseReservationId.newId();
            requests.add(new Request(id, callback));
            return VerseReservationSnapshot.pending(id, verseName);
        }
        Request next() { return requests.removeFirst(); }
        record Request(VerseReservationId id, VerseCompletionSink<VerseReservationSnapshot> callback) { }
    }

    private static final class RecordingLevelResolver implements NeoForgeVerseTravelAdapter.ServerLevelResolver {
        private final GameTestHelper helper;
        private ReadyVerseLease expectedLease;
        private ReadyVerseLease resolvedLease;
        RecordingLevelResolver(GameTestHelper helper) { this.helper = helper; }
        void expect(ReadyVerseLease lease) { expectedLease = lease; }
        @Override public net.minecraft.server.level.ServerLevel resolve(ReadyVerseLease lease) {
            if (lease != expectedLease) throw new AssertionError("adapter resolved a different lease");
            resolvedLease = lease;
            return helper.getLevel();
        }
    }

    private static final class TestServerPlayer extends ServerPlayer {
        TestServerPlayer(net.minecraft.server.MinecraftServer server, net.minecraft.server.level.ServerLevel level,
                         GameProfile profile, net.minecraft.server.level.ClientInformation information) {
            super(server, level, profile, information);
        }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isCreative() { return true; }
    }

    private static final class RecordingSource implements CommandSource {
        final List<String> successes = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { successes.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
        void clear() { successes.clear(); }
    }
}
