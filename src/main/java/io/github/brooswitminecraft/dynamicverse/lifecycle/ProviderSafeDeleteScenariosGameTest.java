package io.github.brooswitminecraft.dynamicverse.lifecycle;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-process (single-JVM) real-provider/real-store acceptance for the four
 * safe-delete scenarios requested separately from the two-JVM crash fixture
 * (MINECRAFT-470 PR3 review, authorized for preparation under 51562/51571/
 * 51577/51588): success, occupied-refusal (a real player present in the
 * target), busy-refusal (an outstanding real read lease on the target), and
 * exact-target FAILED_SAVE (via the real {@code TestOnlySaveFailureHook}).
 * Each test calls {@link ServerVerseRuntime#create(net.minecraft.server.MinecraftServer)}
 * for real production wiring -- the real {@code ManagedVerseStateFileStore}
 * and the real {@code DeleteGateway}/provider bridge, not the
 * {@code InMemoryManagedVerseStateStore}/fake gateway the existing
 * {@code NeoForgeVerseCommandsGameTest} confirmed-delete test uses -- and
 * drives its own runtime's {@code tick()} itself, mirroring
 * {@link ProviderExplicitSuperflatGameTest}. No part of this class has been
 * run; see docs/real-provider-safe-delete-acceptance-plan-20261104.md.
 */
public final class ProviderSafeDeleteScenariosGameTest {
    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public void confirmedDeleteSucceedsAgainstTheRealProvider(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String name = "rp_scenario_success";
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dynamicverse", name);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        var runtime = ServerVerseRuntime.create(server);
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> createOutcome = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.DeleteOutcome> deleteOutcome = new AtomicReference<>();
        AtomicInteger deleteCompletions = new AtomicInteger();
        int[] phase = {0};
        require(runtime.beginCreate(name, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), createOutcome::set)
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "success scenario: create admitted");
        helper.startSequence().thenWaitUntil(() -> {
            runtime.tick();
            if (phase[0] == 0) {
                helper.assertTrue(createOutcome.get() != null, "waiting for create terminal");
                require(createOutcome.get() == VerseLifecycleCommandService.CreateOutcome.READY, "create reaches READY");
                require(runtime.beginConfirmedDelete(name, outcome -> { deleteCompletions.incrementAndGet(); deleteOutcome.set(outcome); })
                                == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                        "confirmed delete admitted against a real, READY, player-free, lease-free verse");
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                helper.assertTrue(deleteOutcome.get() != null, "waiting for delete terminal");
                require(deleteCompletions.get() == 1 && deleteOutcome.get() == VerseLifecycleCommandService.DeleteOutcome.DELETED,
                        "a real, unobstructed confirmed delete completes exactly once as DELETED");
                require(runtime.listManaged().stream().noneMatch(item -> item.name().equals(id.toString())),
                        "a successful real delete removes its own catalog entry");
                require(server.getLevel(key) == null, "a successful real delete leaves the target unloaded");
                System.out.println("RP_SCENARIO_SUCCESS_ACCEPTED name=" + name);
            }
        }).thenExecute(runtime::close).thenSucceed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public void confirmedDeleteIsRefusedWhileARealPlayerOccupiesTheTarget(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String name = "rp_scenario_occupied";
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dynamicverse", name);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        var runtime = ServerVerseRuntime.create(server);
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> createOutcome = new AtomicReference<>();
        int[] phase = {0};
        require(runtime.beginCreate(name, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), createOutcome::set)
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "occupied scenario: create admitted");
        helper.startSequence().thenWaitUntil(() -> {
            runtime.tick();
            if (phase[0] == 0) {
                helper.assertTrue(createOutcome.get() != null, "waiting for create terminal");
                require(createOutcome.get() == VerseLifecycleCommandService.CreateOutcome.READY, "create reaches READY");
                ServerLevel level = server.getLevel(key);
                require(level != null, "the target's real ServerLevel is loaded once READY");
                // A real ServerPlayer (NeoForge's FakePlayer -- a real ServerPlayer
                // subtype with no network connection, not a test double for the
                // delete path itself) added to the target's own player list, the
                // exact condition DeleteGateway.minecraft()'s hasPlayers checks.
                ServerPlayer occupant = FakePlayerFactory.getMinecraft(level);
                level.addNewPlayer(occupant);
                require(!level.players().isEmpty(), "the target's real player list is now non-empty");
                require(runtime.beginConfirmedDelete(name, outcome -> {
                            throw new IllegalStateException("an occupied target must never reach a delete completion callback");
                        }) == VerseLifecycleCommandService.DeleteAdmission.ERR_PLAYERS_PRESENT,
                        "confirmed delete is refused ERR_PLAYERS_PRESENT while a real player occupies the target");
                require(runtime.listManaged().stream().anyMatch(item -> item.name().equals(id.toString())
                                && item.state() == ManagedVerseListing.State.READY),
                        "a refused-at-admission delete leaves the target's catalog entry untouched and READY");
                System.out.println("RP_SCENARIO_OCCUPIED_ACCEPTED name=" + name);
            }
        }).thenExecute(runtime::close).thenSucceed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public void confirmedDeleteIsRefusedWhileARealReadLeaseIsOutstanding(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String name = "rp_scenario_busy";
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("dynamicverse", name);
        var runtime = ServerVerseRuntime.create(server);
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> createOutcome = new AtomicReference<>();
        AtomicReference<VerseReservationSnapshot> acquired = new AtomicReference<>();
        int[] phase = {0};
        require(runtime.beginCreate(name, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), createOutcome::set)
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "busy scenario: create admitted");
        helper.startSequence().thenWaitUntil(() -> {
            runtime.tick();
            if (phase[0] == 0) {
                helper.assertTrue(createOutcome.get() != null, "waiting for create terminal");
                require(createOutcome.get() == VerseLifecycleCommandService.CreateOutcome.READY, "create reaches READY");
                runtime.beginAcquireReadyRead(name, acquired::set);
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                helper.assertTrue(acquired.get() != null, "waiting for the real read-lease acquisition terminal");
                require(acquired.get().lease().isPresent(), "a READY verse grants a real read lease");
                ReadyVerseLease lease = acquired.get().lease().orElseThrow();
                try {
                    // The gate's own busy guard (VerseOperationGate.beginDelete:
                    // "entry.reads != 0") is keyed on an outstanding READ
                    // reservation specifically -- not merely on readiness state,
                    // which an outstanding read does not change. This is the one
                    // deterministic, real (non-mocked) way to reach
                    // DeleteAdmission.ERR_VERSE_BUSY via gate.beginDelete's own
                    // IllegalStateException, distinct from ERR_VERSE_NOT_READY.
                    require(runtime.beginConfirmedDelete(name, outcome -> {
                                throw new IllegalStateException("a busy-refused delete must never reach a completion callback");
                            }) == VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_BUSY,
                            "confirmed delete is refused ERR_VERSE_BUSY while a real read lease is outstanding");
                    require(runtime.listManaged().stream().anyMatch(item -> item.name().equals(id.toString())
                                    && item.state() == ManagedVerseListing.State.READY),
                            "a busy-refused delete leaves the target's catalog entry untouched and READY");
                } finally {
                    lease.close();
                }
                System.out.println("RP_SCENARIO_BUSY_ACCEPTED name=" + name);
            }
        }).thenExecute(runtime::close).thenSucceed();
    }

    @GameTest(templateNamespace = "dynamicverse", template = "empty", timeoutTicks = 400)
    @PrefixGameTestTemplate(false)
    public void confirmedDeleteSurfacesAnExactTargetFailedSaveAsQuarantineWithoutAffectingAnUnrelatedTarget(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        String unrelatedName = "rp_scenario_failed_save_unrelated";
        String targetName = "rp_scenario_failed_save_target";
        ResourceLocation unrelatedId = ResourceLocation.fromNamespaceAndPath("dynamicverse", unrelatedName);
        ResourceLocation targetId = ResourceLocation.fromNamespaceAndPath("dynamicverse", targetName);
        ResourceKey<Level> targetKey = ResourceKey.create(Registries.DIMENSION, targetId);
        var root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        var targetStorage = net.minecraft.world.level.dimension.DimensionType.getStorageFolder(targetKey, root);
        var runtime = ServerVerseRuntime.create(server);
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> unrelatedCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.CreateOutcome> targetCreate = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.DeleteOutcome> unrelatedDelete = new AtomicReference<>();
        AtomicReference<VerseLifecycleCommandService.DeleteOutcome> targetDelete = new AtomicReference<>();
        int[] phase = {0};
        require(runtime.beginCreate(unrelatedName, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), unrelatedCreate::set)
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "FAILED_SAVE scenario: unrelated create admitted");
        require(runtime.beginCreate(targetName, VerseLifecycleCommandService.CreateType.NORMAL, OptionalLong.empty(), targetCreate::set)
                        == VerseLifecycleCommandService.CreateAdmission.ADMITTED,
                "FAILED_SAVE scenario: target create admitted");
        helper.startSequence().thenWaitUntil(() -> {
            runtime.tick();
            if (phase[0] == 0) {
                helper.assertTrue(unrelatedCreate.get() != null && targetCreate.get() != null, "waiting for both create terminals");
                require(unrelatedCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY
                                && targetCreate.get() == VerseLifecycleCommandService.CreateOutcome.READY,
                        "both unrelated and target creates reach READY");
                // The unrelated verse is deleted FIRST, before the hook is armed
                // for anything, proving ordinary success is unaffected by this
                // scenario's later arming.
                require(runtime.beginConfirmedDelete(unrelatedName, unrelatedDelete::set)
                                == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                        "unrelated confirmed delete admitted");
                phase[0] = 1;
            }
            if (phase[0] == 1) {
                helper.assertTrue(unrelatedDelete.get() != null, "waiting for the unrelated delete terminal");
                require(unrelatedDelete.get() == VerseLifecycleCommandService.DeleteOutcome.DELETED,
                        "the unrelated verse deletes normally, unaffected by this scenario");
                // Armed by the EXACT dimension-location string
                // (ResourceLocation#toString -- "dynamicverse:rp_scenario_failed_save_target",
                // confirmed by reading TestOnlySaveFailureHook directly), and only
                // for the target -- the unrelated verse above was never a match
                // and so never consumed this single-fire-per-JVM hook.
                System.setProperty("dynamicdimensions.testOnlyFailSaveForKey", targetId.toString());
                require(runtime.beginConfirmedDelete(targetName, targetDelete::set)
                                == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                        "target confirmed delete admitted (the armed failure fires during the forced save, not at admission)");
                phase[0] = 2;
            }
            if (phase[0] == 2) {
                helper.assertTrue(targetDelete.get() != null, "waiting for the target delete terminal");
                require(targetDelete.get() == VerseLifecycleCommandService.DeleteOutcome.QUARANTINED,
                        "an exact-target armed FAILED_SAVE surfaces as QUARANTINED, never as a silent DELETED");
                require(runtime.listManaged().stream().anyMatch(item -> item.name().equals(targetId.toString())
                                && item.state() == ManagedVerseListing.State.QUARANTINED),
                        "the quarantined target's catalog entry is retained, not dropped");
                require(server.getLevel(targetKey) == null, "a quarantined target is never left loaded as a live level");
                require(Files.isDirectory(targetStorage, LinkOption.NOFOLLOW_LINKS),
                        "an armed FAILED_SAVE must never have removed the target's on-disk storage");
                System.out.println("RP_SCENARIO_FAILED_SAVE_ACCEPTED target=" + targetName + " unrelated=" + unrelatedName);
            }
        }).thenExecute(runtime::close).thenSucceed();
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
