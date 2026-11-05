package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-process (single-JVM) real-provider/real-store acceptance for the four
 * safe-delete scenarios requested separately from the two-JVM crash fixture
 * (MINECRAFT-470 PR3 review, authorized for preparation under 51562/51571/
 * 51577/51588, corrected per 51596): success, occupied-refusal (a real
 * player present in the target), busy-refusal (an outstanding real read
 * lease on the target), and exact-target FAILED_SAVE (via the real
 * {@code TestOnlySaveFailureHook}).
 *
 * <p>Runs as ONE {@code @GameTest} method against ONE
 * {@link ServerVerseRuntime#create(net.minecraft.server.MinecraftServer)}
 * instance, sequenced phase-by-phase, rather than four independent runtime
 * instances: every runtime constructor replays/reconciles the SAME on-disk
 * durable state file, so four concurrent constructors (plus
 * {@code DynamicVerse}'s own bootstrap instance, suppressed here via
 * {@code FixtureSelection.ownsRuntime()}) could otherwise race against each
 * other's in-flight operations. Real production wiring throughout -- the
 * real {@code ManagedVerseStateFileStore} and real {@code DeleteGateway}/
 * provider bridge -- not the {@code InMemoryManagedVerseStateStore}/fake
 * gateway the existing {@code NeoForgeVerseCommandsGameTest} confirmed-
 * delete test uses.</p>
 *
 * <p>Cleanup (closing the runtime, releasing any open lease, removing the
 * fixture's {@code FakePlayer}, restoring the armed system property) is
 * registered once via {@link GameTestInfo#addListener}, whose
 * {@code testPassed}/{@code testFailed} callbacks fire for EVERY terminal
 * outcome including a genuine framework timeout -- not only the success
 * path a {@code .thenExecute(...)} step at the end of a sequence would
 * reach.</p>
 *
 * <p>No part of this class has been run; see
 * docs/real-provider-safe-delete-acceptance-plan-20261104.md.</p>
 */
public final class ProviderSafeDeleteScenariosGameTest {
    /** ServerVerseRuntime.ownedCreateId permits at most 32 chars; see NamingTest. */
    static final String SUCCESS_NAME = "rp_scenario_success";
    static final String OCCUPIED_NAME = "rp_scenario_occupied";
    static final String BUSY_NAME = "rp_scenario_busy";
    static final String FS_TARGET_NAME = "rp_scenario_fs_target";
    static final String FS_UNRELATED_NAME = "rp_scenario_fs_unrelated";
    static final String[] ALL_FIXED_NAMES = {SUCCESS_NAME, OCCUPIED_NAME, BUSY_NAME, FS_TARGET_NAME, FS_UNRELATED_NAME};
    private static final String FAILED_SAVE_PROPERTY = "dynamicdimensions.testOnlyFailSaveForKey";

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 2000)
    @PrefixGameTestTemplate(false)
    public void realProviderSafeDeleteScenarios(GameTestHelper helper) {
        for (String name : ALL_FIXED_NAMES) {
            require(name.matches("[a-z0-9_-]{1,32}"), "fixed scenario name violates ServerVerseRuntime.ownedCreateId's own naming contract: '" + name + "' (" + name.length() + " chars)");
        }
        var server = helper.getLevel().getServer();
        var runtime = ServerVerseRuntime.create(server);
        var state = ManagedVerseStateFileStore.minecraft(server);
        ResourceKey<Level> occupiedKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", OCCUPIED_NAME));
        ResourceKey<Level> fsTargetKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", FS_TARGET_NAME));
        ResourceLocation fsTargetId = fsTargetKey.location();

        AtomicReference<ReadyVerseLease> openLease = new AtomicReference<>();
        AtomicReference<ServerPlayer> occupant = new AtomicReference<>();
        String originalFailedSaveProperty = System.getProperty(FAILED_SAVE_PROPERTY);

        // Registered once, covers every terminal outcome -- success, a failed
        // assertion, or a genuine GameTest-framework timeout -- not only
        // whichever single path a trailing .thenExecute(...) step would
        // reach. compareAndSet makes this safe to invoke more than once.
        AtomicBoolean cleanedUp = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!cleanedUp.compareAndSet(false, true)) return;
            ReadyVerseLease lease = openLease.getAndSet(null);
            if (lease != null) try { lease.close(); } catch (RuntimeException ignored) { }
            ServerPlayer player = occupant.getAndSet(null);
            if (player != null) try { player.discard(); } catch (RuntimeException ignored) { }
            if (originalFailedSaveProperty == null) System.clearProperty(FAILED_SAVE_PROPERTY);
            else System.setProperty(FAILED_SAVE_PROPERTY, originalFailedSaveProperty);
            try { runtime.close(); } catch (RuntimeException ignored) { }
        };
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) { }
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) { cleanup.run(); }
            @Override public void testAddedForRerun(GameTestInfo info, GameTestInfo newInfo, GameTestRunner runner) { }
        });

        AtomicReference<VerseLifecycleCommandService.CreateOutcome> successCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> occupiedCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> busyCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> fsTargetCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> fsUnrelatedCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.DeleteOutcome> successDelete = new AtomicReference<>();
        AtomicReference<VerseReservationSnapshot> busyAcquired = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.DeleteOutcome> fsUnrelatedDelete = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.DeleteOutcome> fsTargetDelete = new AtomicReference<>();
        AtomicLong occupiedReadyGeneration = new AtomicLong(-1);
        AtomicLong busyReadyGeneration = new AtomicLong(-1);
        int[] phase = {0};

        require(runtime.beginCreate(SUCCESS_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), successCreate::set)
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "success scenario: create admitted");

        helper.startSequence().thenWaitUntil(() -> {
            runtime.tick();
            switch (phase[0]) {
                case 0 -> {
                    helper.assertTrue(successCreate.get() != null, "waiting for success-scenario create terminal");
                    require(successCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY, "success-scenario create reaches READY");
                    require(runtime.beginConfirmedDelete(SUCCESS_NAME, successDelete::set)
                                    == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                            "confirmed delete admitted against a real, READY, unobstructed verse");
                    phase[0] = 1;
                }
                case 1 -> {
                    helper.assertTrue(successDelete.get() != null, "waiting for success-scenario delete terminal");
                    require(successDelete.get() == VerseLifecycleCommandService.DeleteOutcome.DELETED,
                            "a real, unobstructed confirmed delete completes exactly once as DELETED");
                    String successKey = ResourceLocation.fromNamespaceAndPath("dynamicverse", SUCCESS_NAME).toString();
                    require(runtime.listManaged().stream().noneMatch(item -> item.name().equals(successKey)),
                            "a successful real delete removes its own catalog entry");
                    require(state.read().ready().values().stream().noneMatch(ready -> ready.id().equals(successKey))
                                    && state.read().operations().values().stream().noneMatch(op -> op.destination().equals(successKey)),
                            "a successful real delete removes its own durable READY entry and operation intent");
                    var successStorage = DimensionType.getStorageFolder(
                            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", SUCCESS_NAME)),
                            server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
                    require(!Files.exists(successStorage, LinkOption.NOFOLLOW_LINKS),
                            "a successful real delete actually removes its own on-disk storage directory");
                    System.out.println("RP_SCENARIO_SUCCESS_ACCEPTED name=" + SUCCESS_NAME);

                    require(runtime.beginCreate(OCCUPIED_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), occupiedCreate::set)
                                    == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "occupied scenario: create admitted");
                    phase[0] = 2;
                }
                case 2 -> {
                    helper.assertTrue(occupiedCreate.get() != null, "waiting for occupied-scenario create terminal");
                    require(occupiedCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY, "occupied-scenario create reaches READY");
                    ServerLevel level = server.getLevel(occupiedKey);
                    require(level != null, "the occupied target's real ServerLevel is loaded once READY");
                    String occupiedKeyString = occupiedKey.location().toString();
                    occupiedReadyGeneration.set(state.read().ready().get(occupiedKeyString).generation());
                    // A real ServerPlayer (NeoForge's FakePlayer -- a real
                    // ServerPlayer subtype with no network connection, not a test
                    // double for the delete path itself) added to the target's own
                    // player list, the exact condition DeleteGateway.minecraft()'s
                    // hasPlayers checks.
                    ServerPlayer fake = FakePlayerFactory.getMinecraft(level);
                    level.addNewPlayer(fake);
                    occupant.set(fake);
                    require(!level.players().isEmpty(), "the occupied target's real player list is now non-empty");
                    require(runtime.beginConfirmedDelete(OCCUPIED_NAME, outcome -> {
                                throw new IllegalStateException("an occupied target must never reach a delete completion callback");
                            }) == VerseLifecycleCommandService.DeleteAdmission.ERR_PLAYERS_PRESENT,
                            "confirmed delete is refused ERR_PLAYERS_PRESENT while a real player occupies the target");
                    require(runtime.listManaged().stream().anyMatch(item -> item.name().equals(occupiedKeyString)
                                    && item.state() == ManagedVerseListing.State.READY),
                            "a refused-at-admission delete leaves the occupied target's catalog entry untouched and READY");
                    require(state.read().ready().get(occupiedKeyString).generation() == occupiedReadyGeneration.get(),
                            "a refused-at-admission delete leaves the occupied target's durable READY generation unchanged");
                    System.out.println("RP_SCENARIO_OCCUPIED_ACCEPTED name=" + OCCUPIED_NAME);

                    require(runtime.beginCreate(BUSY_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), busyCreate::set)
                                    == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "busy scenario: create admitted");
                    phase[0] = 3;
                }
                case 3 -> {
                    helper.assertTrue(busyCreate.get() != null, "waiting for busy-scenario create terminal");
                    require(busyCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY, "busy-scenario create reaches READY");
                    runtime.beginAcquireReadyRead(BUSY_NAME, busyAcquired::set);
                    phase[0] = 4;
                }
                case 4 -> {
                    helper.assertTrue(busyAcquired.get() != null, "waiting for the real read-lease acquisition terminal");
                    require(busyAcquired.get().lease().isPresent(), "a READY busy-scenario verse grants a real read lease");
                    openLease.set(busyAcquired.get().lease().orElseThrow());
                    String busyKeyString = ResourceLocation.fromNamespaceAndPath("dynamicverse", BUSY_NAME).toString();
                    busyReadyGeneration.set(state.read().ready().get(busyKeyString).generation());
                    // VerseOperationGate.beginDelete's own guard ("entry.reads !=
                    // 0") is keyed on an outstanding READ reservation specifically
                    // -- readiness() itself ignores the reads counter, so an
                    // earlier draft's assumption (call beginConfirmedDelete twice
                    // on the same key) would actually hit ERR_VERSE_NOT_READY, not
                    // ERR_VERSE_BUSY, since the first call already flips
                    // entry.ready false. An open, un-closed real read lease is the
                    // actual deterministic path to ERR_VERSE_BUSY.
                    require(runtime.beginConfirmedDelete(BUSY_NAME, outcome -> {
                                throw new IllegalStateException("a busy-refused delete must never reach a completion callback");
                            }) == VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_BUSY,
                            "confirmed delete is refused ERR_VERSE_BUSY while a real read lease is outstanding");
                    require(runtime.listManaged().stream().anyMatch(item -> item.name().equals(busyKeyString)
                                    && item.state() == ManagedVerseListing.State.READY),
                            "a busy-refused delete leaves the busy target's catalog entry untouched and READY");
                    require(state.read().ready().get(busyKeyString).generation() == busyReadyGeneration.get(),
                            "a busy-refused delete leaves the busy target's durable READY generation unchanged");
                    ReadyVerseLease lease = openLease.getAndSet(null);
                    lease.close();
                    System.out.println("RP_SCENARIO_BUSY_ACCEPTED name=" + BUSY_NAME);

                    // Armed BEFORE either FAILED_SAVE verse is even created, and
                    // before the UNRELATED (non-matching) target is exercised --
                    // deleting the unrelated target only while the hook is ALREADY
                    // armed for the exact-target key actually proves a
                    // non-matching key leaves the armed hook unconsumed. Deleting
                    // the unrelated target BEFORE arming anything would only prove
                    // ordinary success, not hook inertness against a mismatch.
                    System.setProperty(FAILED_SAVE_PROPERTY, fsTargetId.toString());
                    require(runtime.beginCreate(FS_TARGET_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), fsTargetCreate::set)
                                    == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "FAILED_SAVE scenario: target create admitted");
                    require(runtime.beginCreate(FS_UNRELATED_NAME, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), fsUnrelatedCreate::set)
                                    == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "FAILED_SAVE scenario: unrelated create admitted");
                    phase[0] = 5;
                }
                case 5 -> {
                    helper.assertTrue(fsTargetCreate.get() != null && fsUnrelatedCreate.get() != null,
                            "waiting for both FAILED_SAVE-scenario create terminals");
                    require(fsTargetCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY
                                    && fsUnrelatedCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY,
                            "both FAILED_SAVE target and unrelated creates reach READY, with the hook already armed for the target only");
                    require(runtime.beginConfirmedDelete(FS_UNRELATED_NAME, fsUnrelatedDelete::set)
                                    == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                            "unrelated confirmed delete admitted while the hook is armed for a DIFFERENT key");
                    phase[0] = 6;
                }
                case 6 -> {
                    helper.assertTrue(fsUnrelatedDelete.get() != null, "waiting for the FAILED_SAVE-scenario unrelated delete terminal");
                    require(fsUnrelatedDelete.get() == VerseLifecycleCommandService.DeleteOutcome.DELETED,
                            "a non-matching key's real save is never touched by a hook armed for a different exact key -- the unrelated verse deletes normally");
                    require(runtime.beginConfirmedDelete(FS_TARGET_NAME, fsTargetDelete::set)
                                    == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                            "target confirmed delete admitted (the armed failure fires during the forced save, not at admission)");
                    phase[0] = 7;
                }
                case 7 -> {
                    helper.assertTrue(fsTargetDelete.get() != null, "waiting for the FAILED_SAVE-scenario target delete terminal");
                    require(fsTargetDelete.get() == VerseLifecycleCommandService.DeleteOutcome.QUARANTINED,
                            "an exact-target armed FAILED_SAVE surfaces as QUARANTINED, never as a silent DELETED");
                    String fsTargetKeyString = fsTargetId.toString();
                    require(runtime.listManaged().stream().anyMatch(item -> item.name().equals(fsTargetKeyString)
                                    && item.state() == ManagedVerseListing.State.QUARANTINED),
                            "the quarantined target's catalog entry is retained, not dropped");
                    // The provider's own QuiesceAndCloseOrchestrator returns
                    // FAILED_SAVE/SAVE immediately on a save failure, BEFORE ever
                    // reaching its close step (confirmed by reading
                    // QuiesceAndCloseOrchestrator.execute and its own state-machine
                    // test's "FAILED_SAVE/SAVE/READY" expectation) -- the source
                    // level is never closed. Runtime-level quarantine revokes gate
                    // readiness bookkeeping; it does not invent an unload. An
                    // earlier draft incorrectly asserted the opposite.
                    require(server.getLevel(fsTargetKey) != null,
                            "a FAILED_SAVE-quarantined target's real source level remains loaded -- the provider never attempted to close it");
                    require(state.read().ready().containsKey(fsTargetKeyString),
                            "a FAILED_SAVE-quarantined target's durable READY metadata is retained, never silently dropped");
                    var targetStorage = DimensionType.getStorageFolder(fsTargetKey, server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize());
                    require(Files.isDirectory(targetStorage, LinkOption.NOFOLLOW_LINKS),
                            "an armed FAILED_SAVE must never have removed the target's on-disk storage");
                    AtomicReference<VerseReservationSnapshot> deniedLease = new AtomicReference<>();
                    runtime.beginAcquireReadyRead(FS_TARGET_NAME, deniedLease::set);
                    runtime.tick();
                    require(deniedLease.get() != null && deniedLease.get().lease().isEmpty(),
                            "a quarantined target denies any new real read lease");
                    System.out.println("RP_SCENARIO_FAILED_SAVE_ACCEPTED target=" + FS_TARGET_NAME + " unrelated=" + FS_UNRELATED_NAME);
                    phase[0] = 8;
                }
                default -> { }
            }
            helper.assertTrue(phase[0] >= 8, "waiting for the final phase");
        }).thenSucceed();
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
