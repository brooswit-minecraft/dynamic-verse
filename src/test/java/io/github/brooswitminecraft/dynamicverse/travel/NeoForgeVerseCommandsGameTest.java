package io.github.brooswitminecraft.dynamicverse.travel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandExceptionType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseCompletionSink;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationId;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ServerVerseRuntime;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseOperationGate;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderDuplicationBridge;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionCopyException;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.OwnedDimensionDescriptor;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLeaseFixtures;
import io.github.brooswitminecraft.dynamicverse.lifecycle.InMemoryManagedVerseStateStore;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.Level;
import dev.galacticraft.dynamicdimensions.api.DynamicDimensionProperties;
import org.joml.Vector3f;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /** Compiled platform-boundary coverage for the same /verse tree's lifecycle literals; no server run is implied here. */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void lifecycleDuplicateAndListBoundary(GameTestHelper helper) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        RecordingSource feedback = new RecordingSource();
        ControlledLifecycle lifecycle = new ControlledLifecycle();
        NeoForgeVerseCommands.register(dispatcher, new PendingAccess(), lease -> helper.getLevel(), lifecycle);
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> duplicate = dispatcher.getRoot().getChild("verse").getChild("duplicate");
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> sourceNode = duplicate.getChild("source");
        com.mojang.brigadier.tree.CommandNode<CommandSourceStack> nameNode = sourceNode.getChild("name");
        helper.assertTrue(((com.mojang.brigadier.tree.ArgumentCommandNode<?, ?>) sourceNode).getType() instanceof net.minecraft.commands.arguments.ResourceLocationArgument
                        && ((com.mojang.brigadier.tree.ArgumentCommandNode<?, ?>) nameNode).getType() instanceof net.minecraft.commands.arguments.ResourceLocationArgument,
                "duplicate nodes must use the built-in serializable resource-location argument type");
        CommandSourceStack operator = source(feedback, null, helper, 2);
        CommandSourceStack denied = source(feedback, null, helper, 1);
        expectRejected(() -> dispatcher.execute("verse list", denied), CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(), "lifecycle permission denial");
        expectRejected(() -> dispatcher.execute("verse delete dynamicverse:alpha", denied), CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(), "delete permission denial");
        helper.assertTrue(lifecycle.deleteAdmissions == 0, "permission denial must not admit delete work");
        dispatcher.execute("verse list", operator);
        helper.assertTrue(feedback.successes.equals(List.of("managed verses: dynamicverse:alpha=READY, dynamicverse:pending=PENDING")), "list must expose state without a live identity");
        feedback.clear();
        try {
            dispatcher.execute("verse duplicate dynamicverse:alpha dynamicverse:bad?", operator);
            throw new AssertionError("malformed duplicate identifier unexpectedly executed");
        } catch (CommandSyntaxException expected) {
            helper.assertTrue(lifecycle.admissions == 0, "malformed identifier must not admit lifecycle work");
        }
        dispatcher.execute("verse duplicate dynamicverse:alpha dynamicverse:copy", operator);
        helper.assertTrue(feedback.successes.equals(List.of("duplicate accepted/pending")), "admission must not claim terminal success");
        helper.assertTrue(lifecycle.source.equals("dynamicverse:alpha") && lifecycle.destination.equals("dynamicverse:copy"), "explicit owned names must reach lifecycle unchanged");
        lifecycle.complete(VerseLifecycleCommandService.DuplicateOutcome.LOADED);
        lifecycle.complete(VerseLifecycleCommandService.DuplicateOutcome.QUARANTINED);
        helper.assertTrue(feedback.successes.equals(List.of("duplicate accepted/pending", "duplicate loaded")), "terminal feedback must be exactly once");
        feedback.clear(); lifecycle.admission = VerseLifecycleCommandService.DuplicateAdmission.ERR_PROVIDER_UNAVAILABLE;
        dispatcher.execute("verse duplicate dynamicverse:alpha dynamicverse:copy", operator);
        helper.assertTrue(feedback.successes.equals(List.of("duplicate failed: ERR_PROVIDER_UNAVAILABLE")), "unavailable provider must fail closed without a pending claim");
        feedback.clear(); lifecycle.admission = VerseLifecycleCommandService.DuplicateAdmission.ADMITTED;
        dispatcher.execute("verse duplicate alpha copy", operator);
        helper.assertTrue(lifecycle.source.equals("alpha") && lifecycle.destination.equals("copy")
                && feedback.successes.equals(List.of("duplicate accepted/pending")), "bare owned names must reach the same lifecycle service unchanged");
        feedback.clear();
        dispatcher.execute("verse delete dynamicverse:alpha", operator);
        helper.assertTrue(lifecycle.deleteName.equals("dynamicverse:alpha")
                        && feedback.successes.equals(List.of("delete failed: ERR_SAFE_DELETE_UNAVAILABLE")),
                "eligible owned delete must fail closed before a delete success claim");
        feedback.clear(); lifecycle.deleteAdmission = VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_NOT_MANAGED;
        dispatcher.execute("verse delete orphan", operator);
        helper.assertTrue(lifecycle.deleteName.equals("orphan")
                        && feedback.successes.equals(List.of("delete failed: ERR_VERSE_NOT_MANAGED")),
                "untracked owned spelling must be refused without a pending claim");
        try {
            dispatcher.execute("verse delete minecraft:overworld", operator);
            throw new AssertionError("foreign delete identifier unexpectedly reached lifecycle service");
        } catch (CommandSyntaxException expected) {
            helper.assertTrue(lifecycle.deleteAdmissions == 2, "foreign vanilla identifier must not reach delete admission");
        }

        int confirmedBefore = lifecycle.confirmedDeleteAdmissions;
        feedback.clear(); lifecycle.deleteAdmission = VerseLifecycleCommandService.DeleteAdmission.ERR_CONFIRMATION_REQUIRED;
        dispatcher.execute("verse delete dynamicverse:alpha", operator);
        helper.assertTrue(feedback.successes.equals(List.of("delete failed: ERR_CONFIRMATION_REQUIRED"))
                        && lifecycle.confirmedDeleteAdmissions == confirmedBefore,
                "a confirmation-required admission must not itself invoke confirmed delete");

        expectRejected(() -> dispatcher.execute("verse delete dynamicverse:alpha confirm", denied),
                CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand(), "confirm permission denial");
        helper.assertTrue(lifecycle.confirmedDeleteAdmissions == confirmedBefore, "permission denial must not admit confirmed delete work");

        feedback.clear(); lifecycle.confirmedDeleteAdmission = VerseLifecycleCommandService.DeleteAdmission.ERR_VERSE_BUSY;
        dispatcher.execute("verse delete dynamicverse:alpha confirm", operator);
        helper.assertTrue(lifecycle.confirmedDeleteName.equals("dynamicverse:alpha")
                        && lifecycle.confirmedDeleteAdmissions == confirmedBefore + 1
                        && feedback.successes.equals(List.of("delete failed: ERR_VERSE_BUSY")),
                "a refused confirmed admission must fail closed without a pending claim");

        feedback.clear(); lifecycle.confirmedDeleteAdmission = VerseLifecycleCommandService.DeleteAdmission.ADMITTED;
        dispatcher.execute("verse delete dynamicverse:alpha confirm", operator);
        helper.assertTrue(feedback.successes.equals(List.of("delete accepted/pending"))
                        && lifecycle.confirmedDeleteAdmissions == confirmedBefore + 2,
                "confirmed admission must not claim terminal success, and confirm reaches the service exactly once per invocation");
        lifecycle.completeDelete(VerseLifecycleCommandService.DeleteOutcome.DELETED);
        lifecycle.completeDelete(VerseLifecycleCommandService.DeleteOutcome.QUARANTINED);
        helper.assertTrue(feedback.successes.equals(List.of("delete accepted/pending", "delete completed")),
                "delete terminal feedback must be exactly once");

        feedback.clear();
        dispatcher.execute("verse delete orphan confirm", operator);
        helper.assertTrue(lifecycle.confirmedDeleteName.equals("orphan")
                        && feedback.successes.equals(List.of("delete accepted/pending")),
                "bare owned names must reach the same confirmed-delete service unchanged");
        lifecycle.completeDelete(VerseLifecycleCommandService.DeleteOutcome.QUARANTINED);
        helper.assertTrue(feedback.successes.equals(List.of("delete accepted/pending", "delete failed: QUARANTINED")),
                "a quarantined confirmed terminal reports failure truthfully");
        helper.succeed();
    }

    /** Compiled-only facade coverage: same runtime/gate/access path, controlled bridge, no provider invocation. */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void runtimeRetiresPendingAccessWithoutProviderRun(GameTestHelper helper) {
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(helper.getLevel().getServer(),
                gateway(new AtomicInteger(), new AtomicInteger(), ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT, null));
        AtomicInteger completions = new AtomicInteger();
        runtime.beginAcquireReadyRead("dynamicverse:missing", result -> {
            helper.assertTrue(result.phase() == io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationPhase.FAILED, "close must settle pending acquire");
            completions.incrementAndGet();
        });
        runtime.close();
        helper.assertTrue(completions.get() == 1, "pending acquire must complete exactly once on close");
        runtime.beginAcquireReadyRead("dynamicverse:missing", result -> completions.incrementAndGet());
        helper.assertTrue(completions.get() == 2, "post-close acquire must refuse immediately");
        helper.assertTrue(runtime.listManaged().isEmpty(), "rejected enrollment cannot publish catalog state");
        helper.succeed();
    }

    /** Compiled-only production-facade identity cases; lookup is controlled, no level lifecycle call occurs. */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void runtimeEnrollmentLeaseAndReservationBoundary(GameTestHelper helper) throws Exception {
        final boolean[] current = {true};
        final AtomicInteger registrations = new AtomicInteger();
        final AtomicInteger duplicateCallbacks = new AtomicInteger();
        ProducedCreate create = new ProducedCreate(helper);
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(helper.getLevel().getServer(), descriptor -> current[0] && descriptor == create.descriptor ? evidence(descriptor, helper) : null,
                gateway(registrations, duplicateCallbacks, ProviderDuplicationBridge.BridgeAdmission.ADMITTED, ProviderDuplicationBridge.BridgeOutcome.RESTORED), create);
        OwnedDimensionDescriptor source = createReady(runtime, create, helper, "source");
        long sourceGeneration = runtime.listManaged().stream().filter(item -> item.name().equals(source.id())).findFirst().orElseThrow().generation().orElseThrow();
        helper.assertTrue(registrations.get() == 0 && runtime.listManaged().size() == 1 && runtime.listManaged().get(0).state() == ManagedVerseListing.State.READY, "omitted-seed producer publishes exactly one READY source through its reservation");
        final VerseReservationSnapshot[] terminal = new VerseReservationSnapshot[1];
        runtime.beginAcquireReadyRead(source.id(), result -> terminal[0] = result); runtime.tick();
        ReadyVerseLease lease = terminal[0].lease().orElseThrow();
        helper.assertTrue(runtime.resolve(lease) == helper.getLevel(), "live owned matching lease resolves current instance");
        lease.close(); helper.assertTrue(runtime.resolve(lease) == null, "closed lease is unavailable");
        ReadyVerseLease foreign = ReadyVerseLeaseFixtures.acquired(source.id(), helper.getLevel().dimension().location()+"@foreign", sourceGeneration, VerseReservationId.newId(), () -> { });
        helper.assertTrue(runtime.resolve(foreign) == null, "foreign lease is unavailable");
        current[0] = false;
        final VerseReservationSnapshot[] stale = new VerseReservationSnapshot[1]; runtime.beginAcquireReadyRead(source.id(), result -> stale[0] = result); runtime.tick();
        ReadyVerseLease staleLease = stale[0].lease().orElseThrow();
        try {
            helper.assertTrue(runtime.resolve(staleLease) == null, "replaced/absent current instance is unavailable");
        } finally {
            staleLease.close();
        }
        final List<VerseLifecycleCommandService.DuplicateOutcome> outcomes = new ArrayList<>();
        helper.assertTrue(runtime.beginDuplicate(source.id(), "dynamicverse:copy", outcomes::add) == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED, "fresh descriptor reservation reaches controlled bridge admission");
        runtime.tick();
        helper.assertTrue(outcomes.equals(List.of(VerseLifecycleCommandService.DuplicateOutcome.RESTORED)) && duplicateCallbacks.get() == 1, "controlled terminal result is delivered exactly once");
        helper.assertTrue(runtime.listManaged().stream().anyMatch(item -> item.name().equals("dynamicverse:copy") && item.state() == ManagedVerseListing.State.QUARANTINED), "restored-only destination is never READY");
        ServerVerseRuntime rejected = ServerVerseRuntime.controlled(helper.getLevel().getServer(), descriptor -> null,
                gateway(new AtomicInteger(), new AtomicInteger(), ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT, null));
        try { rejected.enrollVerifiedReady(provenance(descriptor("rejected", helper), helper, 1)); throw new AssertionError("forged enrollment accepted"); }
        catch (IllegalArgumentException expected) { helper.assertTrue(rejected.listManaged().isEmpty(), "rejected enrollment leaves no catalog mutation"); }
        helper.assertTrue(!runtime.owns(new Object()), "a distinct server identity cannot access this runtime");
        helper.succeed();
    }

    /** Compiled-only held-lease and pending-request retirement evidence through the production facade. */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void runtimeRetiresOpenLeaseAndPendingAccess(GameTestHelper helper) throws Exception {
        ProducedCreate create = new ProducedCreate(helper);
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(helper.getLevel().getServer(), descriptor -> descriptor == create.descriptor ? evidence(descriptor, helper) : null,
                gateway(new AtomicInteger(), new AtomicInteger(), ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT, null), create);
        OwnedDimensionDescriptor source = createReady(runtime, create, helper, "retire-source");
        final VerseReservationSnapshot[] openRead = new VerseReservationSnapshot[1];
        runtime.beginAcquireReadyRead(source.id(), result -> openRead[0] = result); runtime.tick();
        ReadyVerseLease stillOpen = openRead[0].lease().orElseThrow();
        helper.assertTrue(runtime.resolve(stillOpen) == helper.getLevel(), "open owned lease resolves before runtime retirement");
        AtomicInteger pendingCompletions = new AtomicInteger();
        runtime.beginAcquireReadyRead("dynamicverse:pending", result -> {
            helper.assertTrue(result.phase() == io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationPhase.FAILED,
                    "runtime retirement settles a pending request");
            pendingCompletions.incrementAndGet();
        });
        runtime.close(); runtime.close();
        helper.assertTrue(pendingCompletions.get() == 1, "pending retirement must complete exactly once");
        helper.assertTrue(runtime.resolve(stillOpen) == null, "an open lease cannot resolve after runtime retirement");
        stillOpen.close();
        AtomicInteger postClose = new AtomicInteger(); runtime.beginAcquireReadyRead(source.id(), ignored -> postClose.incrementAndGet());
        helper.assertTrue(postClose.get() == 1, "post-close access refuses immediately");
        helper.succeed();
    }

    /**
     * Compiled-only actual-runtime proof of the current delete boundary.  The
     * controlled ports expose counts solely to prove this admission did not
     * invoke provider or storage operations.
     */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void runtimeDeleteAdmissionIsSafeBeforeProviderOrStorageMutation(GameTestHelper helper) {
        ProducedCreate create = new ProducedCreate(helper);
        AtomicInteger bridgeCalls = new AtomicInteger();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(helper.getLevel().getServer(),
                descriptor -> descriptor == create.descriptor ? evidence(descriptor, helper) : null,
                (catalog, gate) -> new ServerVerseRuntime.BridgeGateway() {
                    @Override public void registerReady(String key, String identity, long generation) {
                        bridgeCalls.incrementAndGet(); gate.publishReady(key, identity, generation);
                    }
                    @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination,
                                                                                               java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) {
                        bridgeCalls.incrementAndGet();
                        return ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT;
                    }
                }, create);
        OwnedDimensionDescriptor source = createReady(runtime, create, helper, "delete-source");
        int providerBefore = bridgeCalls.get();
        int canCreateBefore = create.canCreateCalls;
        int storageBefore = create.storageChecks;
        int createBefore = create.createCalls;

        helper.assertTrue(runtime.beginDelete(source.id()) == VerseLifecycleCommandService.DeleteAdmission.ERR_SAFE_DELETE_UNAVAILABLE,
                "a verified READY Dynamic Verse is admitted only to the fail-closed delete result");
        helper.assertTrue(bridgeCalls.get() == providerBefore, "delete admission must not invoke the provider bridge");
        helper.assertTrue(create.canCreateCalls == canCreateBefore && create.storageChecks == storageBefore
                        && create.createCalls == createBefore,
                "delete admission must not probe or mutate the provider/storage create boundary");
        helper.assertTrue(runtime.listManaged().stream().anyMatch(item -> item.name().equals(source.id())
                        && item.state() == ManagedVerseListing.State.READY),
                "fail-closed delete admission must leave the READY catalog entry untouched");
        runtime.close();
        helper.succeed();
    }

    /**
     * Production-wiring proof requested in review of PR3: not the boolean
     * fake-journal SafeDeleteOperationTest, but ServerVerseRuntime's actual
     * delete journal and restart reconciliation, through the real
     * VerseOperationGate.DeleteReservation and ConfinedDimensionDeleter. A
     * successful confirmed delete removes only its own READY/intent, leaving
     * an unrelated owned world untouched; an admitted-but-never-terminated
     * ("interrupted") delete keeps its durable PENDING intent, so a fresh
     * runtime recovering the same persisted store quarantines it rather than
     * ever reloading its stale READY metadata, while the unrelated world
     * still replays normally.
     */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void runtimeConfirmedDeleteRemovesOnlyItsOwnMetadataAndInterruptedDeleteStaysQuarantinedAfterRestart(GameTestHelper helper) throws Exception {
        net.minecraft.server.level.ServerLevel level = helper.getLevel();
        java.util.Set<String> createdIds = new java.util.HashSet<>();
        ServerVerseRuntime.CreateGateway create = new ServerVerseRuntime.CreateGateway() {
            @Override public boolean canCreate(ResourceLocation id) { return true; }
            @Override public boolean storageExists(OwnedDimensionDescriptor descriptor) { return false; }
            @Override public net.minecraft.server.level.ServerLevel create(OwnedDimensionDescriptor descriptor) {
                createdIds.add(descriptor.id()); return level;
            }
            @Override public boolean hasStoredData(OwnedDimensionDescriptor descriptor) { return createdIds.contains(descriptor.id()); }
            @Override public net.minecraft.server.level.ServerLevel reload(OwnedDimensionDescriptor descriptor) {
                return createdIds.contains(descriptor.id()) ? level : null;
            }
        };
        ServerVerseRuntime.LevelLookup lookup = descriptor -> createdIds.contains(descriptor.id()) ? evidence(descriptor, helper) : null;
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        java.nio.file.Path deleteRoot = java.nio.file.Files.createTempDirectory("m470-safe-delete-reconciled-test");
        try {
            java.nio.file.Files.createDirectories(deleteRoot.resolve("dimensions/dynamicverse/delete-a"));
            java.nio.file.Files.createDirectories(deleteRoot.resolve("dimensions/dynamicverse/delete-c"));
            AtomicReference<String> matchingKey = new AtomicReference<>();
            AtomicReference<java.util.function.Consumer<io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionReceipt>> pendingTerminal = new AtomicReference<>();
            ServerVerseRuntime.DeleteGateway deletes = new ServerVerseRuntime.DeleteGateway() {
                @Override public java.nio.file.Path root() { return deleteRoot; }
                @Override public boolean matchesCurrent(io.github.brooswitminecraft.dynamicverse.lifecycle.delete.SafeDeleteOperation.Target target) {
                    return target.key().equals(matchingKey.get());
                }
                @Override public boolean hasPlayers(io.github.brooswitminecraft.dynamicverse.lifecycle.delete.SafeDeleteOperation.Target target) { return false; }
                @Override public boolean isClosedAndAbsent(io.github.brooswitminecraft.dynamicverse.lifecycle.delete.SafeDeleteOperation.Target target) { return true; }
                @Override public boolean beginClose(io.github.brooswitminecraft.dynamicverse.lifecycle.delete.SafeDeleteOperation.Target target,
                        java.util.function.Consumer<io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionReceipt> terminal) {
                    pendingTerminal.set(terminal); return true;
                }
            };
            ServerVerseRuntime.BridgeGatewayFactory bridgeFactory = (catalog, gate) -> new ServerVerseRuntime.BridgeGateway() {
                @Override public void registerReady(String key, String identity, long generation) { gate.publishReady(key, identity, generation); }
                @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String s, String d, java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> c) { return ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT; }
            };
            ServerVerseRuntime runtime = ServerVerseRuntime.controlled(level.getServer(), lookup, bridgeFactory, create, state, deletes);

            String a = createReadyOwnedId(runtime, createdIds, helper, "delete-a");
            String b = createReadyOwnedId(runtime, createdIds, helper, "delete-b");
            String c = createReadyOwnedId(runtime, createdIds, helper, "delete-c");

            // A: a full, successfully-admitted confirmed delete through the real journal/reservation/filesystem removal.
            matchingKey.set(a);
            AtomicInteger aCompletions = new AtomicInteger();
            AtomicReference<VerseLifecycleCommandService.DeleteOutcome> aOutcome = new AtomicReference<>();
            helper.assertTrue(runtime.beginConfirmedDelete(a, outcome -> { aCompletions.incrementAndGet(); aOutcome.set(outcome); })
                            == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                    "an eligible owned verse with a delete gateway is admitted for confirmed delete");
            helper.assertTrue(state.read().operations().values().stream().anyMatch(op -> op.destination().equals(a)),
                    "the admitted delete persists its own durable intent before any filesystem mutation");
            pendingTerminal.get().accept(new io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionReceipt(
                    a, 0, true, io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ClosedDimensionStatus.SAVED_AND_CLOSED, false));
            runtime.tick();
            helper.assertTrue(aCompletions.get() == 1 && aOutcome.get() == VerseLifecycleCommandService.DeleteOutcome.DELETED,
                    "a verified exact close/absence evidence completes the confirmed delete exactly once");
            helper.assertTrue(!java.nio.file.Files.exists(deleteRoot.resolve("dimensions/dynamicverse/delete-a")),
                    "a successful confirmed delete actually removes its own confined storage");
            helper.assertTrue(runtime.listManaged().stream().noneMatch(item -> item.name().equals(a)),
                    "a successful confirmed delete removes its own catalog entry");
            helper.assertTrue(state.read().ready().values().stream().noneMatch(ready -> ready.id().equals(a))
                            && state.read().operations().values().stream().noneMatch(op -> op.destination().equals(a)),
                    "a successful confirmed delete removes only its own persisted READY/intent metadata");
            helper.assertTrue(runtime.listManaged().stream().anyMatch(item -> item.name().equals(b) && item.state() == ManagedVerseListing.State.READY)
                            && state.read().ready().containsKey(b),
                    "an unrelated owned world's catalog/persisted metadata is untouched by another world's confirmed delete");

            // C: admitted (durable intent persisted) but never delivered a terminal -- an interrupted delete.
            matchingKey.set(c);
            AtomicInteger cCompletions = new AtomicInteger();
            helper.assertTrue(runtime.beginConfirmedDelete(c, ignored -> cCompletions.incrementAndGet())
                            == VerseLifecycleCommandService.DeleteAdmission.ADMITTED,
                    "a second eligible owned verse is independently admitted for confirmed delete");
            helper.assertTrue(state.read().operations().values().stream().anyMatch(op -> op.destination().equals(c)
                            && op.phase() == io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseStateStore.OperationPhase.PENDING),
                    "the interrupted delete's durable intent remains PENDING with no terminal delivered");
            helper.assertTrue(cCompletions.get() == 0, "no terminal was delivered before the simulated crash");
            helper.assertTrue(java.nio.file.Files.exists(deleteRoot.resolve("dimensions/dynamicverse/delete-c")),
                    "an interrupted delete never reaches filesystem removal");

            // Simulate restart: a fresh runtime recovers the same persisted store.
            ServerVerseRuntime restarted = ServerVerseRuntime.controlled(level.getServer(), lookup, bridgeFactory, create, state, deletes);
            helper.assertTrue(restarted.listManaged().stream().anyMatch(item -> item.name().equals(c)
                            && item.state() == ManagedVerseListing.State.QUARANTINED),
                    "a fresh runtime recovering the same persisted store quarantines the interrupted delete's stale key, never reloading it as READY");
            VerseReservationSnapshot[] cRead = new VerseReservationSnapshot[1];
            restarted.beginAcquireReadyRead(c, result -> cRead[0] = result); restarted.tick();
            helper.assertTrue(cRead[0].lease().isEmpty(), "the quarantined interrupted-delete key is never readable after restart");
            helper.assertTrue(restarted.listManaged().stream().anyMatch(item -> item.name().equals(b) && item.state() == ManagedVerseListing.State.READY),
                    "the unrelated owned world still replays normally as READY after restart");
            VerseReservationSnapshot[] bRead = new VerseReservationSnapshot[1];
            restarted.beginAcquireReadyRead(b, result -> bRead[0] = result); restarted.tick();
            helper.assertTrue(bRead[0].lease().isPresent(), "the unrelated owned world remains readable after restart");
            bRead[0].lease().get().close();

            runtime.close(); restarted.close();
        } finally {
            deleteDirectoryRecursively(deleteRoot);
        }
        helper.succeed();
    }
    private static String createReadyOwnedId(ServerVerseRuntime runtime, java.util.Set<String> createdIds, GameTestHelper helper, String name) {
        AtomicInteger terminal = new AtomicInteger();
        helper.assertTrue(runtime.beginCreate(name, VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), outcome -> {
            helper.assertTrue(outcome == VerseLifecycleCommandService.CreateOutcome.READY, "controlled producer must publish READY");
            terminal.incrementAndGet();
        }) == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "controlled producer create admitted for " + name);
        runtime.tick();
        String ownedId = "dynamicverse:" + name;
        helper.assertTrue(createdIds.contains(ownedId) && terminal.get() == 1, "controlled producer exposes the exact created descriptor once for " + name);
        return ownedId;
    }
    private static void deleteDirectoryRecursively(java.nio.file.Path root) throws java.io.IOException {
        if (!java.nio.file.Files.exists(root)) return;
        try (var paths = java.nio.file.Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { java.nio.file.Files.delete(path); } catch (java.io.IOException ignored) { }
            });
        }
    }

    /**
     * Compiled-only proof of the reviewed P2 defect: a duplicate rejection known
     * to have no provider-side effect (e.g. the gate refusing a busy source
     * before the provider is ever invoked) must fully release its provisional
     * destination reservation — including the persisted operation intent, so a
     * restart does not wrongly exclude it either — while a rejection reached
     * only after an uncertain provider invocation must preserve it as durable
     * quarantine that survives a restart over the same persisted store.
     */
    @GameTest(templateNamespace = "minecraft", template = "bastion/mobs/empty")
    public static void runtimeReleasesNoEffectDuplicateReservationButPreservesUncertainQuarantine(GameTestHelper helper) {
        ProducedCreate create = new ProducedCreate(helper);
        InMemoryManagedVerseStateStore state = new InMemoryManagedVerseStateStore();
        AtomicReference<ProviderDuplicationBridge.BridgeAdmission> nextAdmission =
                new AtomicReference<>(ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT);
        AtomicInteger bridgeCalls = new AtomicInteger();
        ServerVerseRuntime runtime = ServerVerseRuntime.controlled(helper.getLevel().getServer(),
                descriptor -> descriptor == create.descriptor ? evidence(descriptor, helper) : null,
                (catalog, gate) -> new ServerVerseRuntime.BridgeGateway() {
                    @Override public void registerReady(String key, String identity, long generation) { gate.publishReady(key, identity, generation); }
                    @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination,
                                                                                               java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) {
                        bridgeCalls.incrementAndGet();
                        return nextAdmission.get();
                    }
                }, create, state);
        OwnedDimensionDescriptor source = createReady(runtime, create, helper, "busy-retry-source");

        helper.assertTrue(runtime.beginDuplicate(source.id(), "dynamicverse:no-effect-destination", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_BUSY,
                "a no-effect rejection is reported as busy");
        int bridgeCallsAfterFirstRejection = bridgeCalls.get();
        helper.assertTrue(runtime.listManaged().stream().noneMatch(item -> item.name().equals("dynamicverse:no-effect-destination")),
                "a no-effect rejection releases the provisional destination reservation");
        helper.assertTrue(state.read().operations().values().stream().noneMatch(op -> op.destination().equals("dynamicverse:no-effect-destination")),
                "a no-effect rejection also releases the persisted operation intent, not only the in-memory reservation");

        nextAdmission.set(ProviderDuplicationBridge.BridgeAdmission.ADMITTED);
        helper.assertTrue(runtime.beginDuplicate(source.id(), "dynamicverse:no-effect-destination", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ADMITTED,
                "the same destination name is admitted again once the no-effect rejection cause clears");
        helper.assertTrue(bridgeCalls.get() == bridgeCallsAfterFirstRejection + 1, "the retry reaches the bridge exactly once");

        nextAdmission.set(ProviderDuplicationBridge.BridgeAdmission.REJECTED_NO_EFFECT);
        helper.assertTrue(runtime.beginDuplicate(source.id(), "dynamicverse:no-effect-restart-destination", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_BUSY,
                "a second, distinct no-effect rejection is also reported as busy");

        nextAdmission.set(ProviderDuplicationBridge.BridgeAdmission.REJECTED_UNCERTAIN);
        helper.assertTrue(runtime.beginDuplicate(source.id(), "dynamicverse:uncertain-destination", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_BUSY,
                "an uncertain-provider rejection is also reported as busy");
        helper.assertTrue(runtime.listManaged().stream().anyMatch(item -> item.name().equals("dynamicverse:uncertain-destination")),
                "an uncertain-provider rejection preserves the provisional destination reservation as quarantine");
        helper.assertTrue(state.read().operations().values().stream().anyMatch(op -> op.destination().equals("dynamicverse:uncertain-destination")),
                "an uncertain-provider rejection keeps its persisted operation intent durable, unlike a no-effect rejection");
        nextAdmission.set(ProviderDuplicationBridge.BridgeAdmission.ADMITTED);
        helper.assertTrue(runtime.beginDuplicate(source.id(), "dynamicverse:uncertain-destination", ignored -> { })
                        == VerseLifecycleCommandService.DuplicateAdmission.ERR_VERSE_ALREADY_EXISTS,
                "a quarantined uncertain destination cannot be silently reused in the same runtime");
        runtime.close();

        ServerVerseRuntime restarted = ServerVerseRuntime.controlled(helper.getLevel().getServer(), descriptor -> null, null, null, state);
        helper.assertTrue(restarted.listManaged().stream().anyMatch(item -> item.name().equals("dynamicverse:uncertain-destination")
                        && item.state() == ManagedVerseListing.State.QUARANTINED),
                "a fresh runtime recovering the same persisted store still excludes the uncertain-provider destination after restart");
        helper.assertTrue(restarted.listManaged().stream().noneMatch(item -> item.name().equals("dynamicverse:no-effect-restart-destination")),
                "a fresh runtime recovering the same persisted store never excludes a released no-effect destination");
        restarted.close();
        helper.succeed();
    }

    private static ServerVerseRuntime.BridgeGatewayFactory gateway(AtomicInteger registrations, AtomicInteger callbacks,
                                                              ProviderDuplicationBridge.BridgeAdmission admission,
                                                              ProviderDuplicationBridge.BridgeOutcome terminal) { return (catalog, gate) -> new ServerVerseRuntime.BridgeGateway() {
        @Override public void registerReady(String key, String identity, long generation) { gate.publishReady(key, identity, generation); registrations.incrementAndGet(); }
        @Override public ProviderDuplicationBridge.BridgeAdmission beginDuplicate(String source, String destination, java.util.function.Consumer<ProviderDuplicationBridge.BridgeOutcome> completion) {
            if (admission == ProviderDuplicationBridge.BridgeAdmission.ADMITTED) {
                VerseOperationGate.Readiness ready = gate.readiness(source);
                VerseOperationGate.Operation operation = gate.beginOperation(source, destination);
                if (terminal != null) {
                    operation.claimTerminal(ready.generation());
                    if (terminal == ProviderDuplicationBridge.BridgeOutcome.RESTORED) operation.restored(ready.identity(), ready.generation());
                    operation.close(); callbacks.incrementAndGet(); completion.accept(terminal);
                }
            }
            return admission;
        }
    }; }
    private static ServerVerseRuntime.CurrentLevelEvidence evidence(OwnedDimensionDescriptor descriptor, GameTestHelper helper) {
        return new ServerVerseRuntime.CurrentLevelEvidence(descriptor.key(), helper.getLevel(), descriptor.generator(), descriptor.type(),
                descriptor.id() + "@" + Integer.toHexString(System.identityHashCode(helper.getLevel())), helper.getLevel().getSeed());
    }
    private static ServerVerseRuntime.EnrollmentProvenance provenance(OwnedDimensionDescriptor descriptor, GameTestHelper helper, long generation) {
        ServerVerseRuntime.CurrentLevelEvidence evidence = evidence(descriptor, helper);
        return new ServerVerseRuntime.EnrollmentProvenance(descriptor, helper.getLevel(), generation, evidence.identity());
    }
    /** Controlled producer for positive Runtime setup; it never invokes a native provider or world lifecycle API. */
    private static OwnedDimensionDescriptor createReady(ServerVerseRuntime runtime, ProducedCreate create, GameTestHelper helper, String name) {
        AtomicInteger terminal = new AtomicInteger();
        helper.assertTrue(runtime.beginCreate(name, VerseLifecycleCommandService.CreateType.NORMAL, java.util.OptionalLong.empty(), outcome -> {
            helper.assertTrue(outcome == VerseLifecycleCommandService.CreateOutcome.READY, "controlled producer must publish READY");
            terminal.incrementAndGet();
        }) == VerseLifecycleCommandService.CreateAdmission.ADMITTED, "controlled producer create admitted");
        runtime.tick();
        helper.assertTrue(create.descriptor != null && terminal.get() == 1, "controlled producer exposes the exact created descriptor once");
        return create.descriptor;
    }
    private static final class ProducedCreate implements ServerVerseRuntime.CreateGateway {
        private final GameTestHelper helper;
        private OwnedDimensionDescriptor descriptor;
        private int canCreateCalls;
        private int storageChecks;
        private int createCalls;
        private ProducedCreate(GameTestHelper helper) { this.helper = helper; }
        @Override public boolean canCreate(ResourceLocation id) { canCreateCalls++; return true; }
        @Override public boolean storageExists(OwnedDimensionDescriptor descriptor) { storageChecks++; return false; }
        @Override public net.minecraft.server.level.ServerLevel create(OwnedDimensionDescriptor descriptor) {
            createCalls++;
            this.descriptor = descriptor;
            return helper.getLevel();
        }
    }
    private static OwnedDimensionDescriptor descriptor(String path, GameTestHelper helper) {
        return new OwnedDimensionDescriptor(ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("dynamicverse", path)), helper.getLevel().getChunkSource().getGenerator(), helper.getLevel().dimensionType(), new DynamicDimensionProperties() {
            public int priority() { return 0; } public Vector3f baseGravity() { return new Vector3f(); } public double basePressure() { return 0; } public float universalDrag() { return 0; } public Vector3f magneticNorth() { return new Vector3f(); }
        });
    }

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

    private static final class ControlledLifecycle implements VerseLifecycleCommandService {
        private java.util.function.Consumer<DuplicateOutcome> completion;
        private DuplicateAdmission admission = DuplicateAdmission.ADMITTED;
        private int admissions;
        private int deleteAdmissions;
        private String source;
        private String destination;
        private String deleteName;
        private DeleteAdmission deleteAdmission = DeleteAdmission.ERR_SAFE_DELETE_UNAVAILABLE;
        private int confirmedDeleteAdmissions;
        private String confirmedDeleteName;
        private DeleteAdmission confirmedDeleteAdmission = DeleteAdmission.ADMITTED;
        private java.util.function.Consumer<DeleteOutcome> deleteCompletion;
        @Override public DuplicateAdmission beginDuplicate(String source, String destination, java.util.function.Consumer<DuplicateOutcome> callback) {
            admissions++; this.source = source; this.destination = destination; completion = callback; return admission;
        }
        @Override public CreateAdmission beginCreate(String name, CreateType type, java.util.OptionalLong seed,
                                                      java.util.function.Consumer<CreateOutcome> callback) {
            return CreateAdmission.ERR_PROVIDER_UNAVAILABLE;
        }
        @Override public DeleteAdmission beginDelete(String name) {
            deleteAdmissions++; deleteName = name; return deleteAdmission;
        }
        @Override public DeleteAdmission beginConfirmedDelete(String name, java.util.function.Consumer<DeleteOutcome> callback) {
            confirmedDeleteAdmissions++; confirmedDeleteName = name; deleteCompletion = callback; return confirmedDeleteAdmission;
        }
        void completeDelete(DeleteOutcome outcome) { deleteCompletion.accept(outcome); }
        @Override public List<ManagedVerseListing> listManaged() {
            return List.of(new ManagedVerseListing("dynamicverse:alpha", ManagedVerseListing.State.READY, java.util.Optional.of("identity"), java.util.Optional.of(1L)),
                    new ManagedVerseListing("dynamicverse:pending", ManagedVerseListing.State.PENDING, java.util.Optional.empty(), java.util.Optional.empty()));
        }
        void complete(DuplicateOutcome outcome) { completion.accept(outcome); }
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
