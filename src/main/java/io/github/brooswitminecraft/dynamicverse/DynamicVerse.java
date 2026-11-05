package io.github.brooswitminecraft.dynamicverse;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ServerVerseRuntime;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessError;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseCompletionSink;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationId;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot;
import io.github.brooswitminecraft.dynamicverse.travel.NeoForgeVerseCommands;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderDuplicationBridgeGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.SeededBridgeRegressionGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderBridgeIntegrationGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderCreateIntegrationGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderExplicitSuperflatGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderSeededDuplicateIntegrationGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderSeededRestartPhaseAGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderSeededRestartPhaseBGameTest;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderOrdinaryRestartFixture;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ProviderSafeDeleteCrashFixture;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.Consumer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Server bootstrap owns one runtime facade and the existing GameTest registrations. */
@Mod(DynamicVerse.MOD_ID)
public final class DynamicVerse {
    public static final String MOD_ID = "dynamicverse";
    private ServerVerseRuntime runtime;
    private ProviderOrdinaryRestartFixture ordinaryRestart;
    private ProviderSafeDeleteCrashFixture safeDeleteCrash;
    private final BootstrapFacade facade = new BootstrapFacade();

    public DynamicVerse(IEventBus modEventBus) {
        modEventBus.addListener(this::registerGameTests);
        NeoForge.EVENT_BUS.addListener(this::serverStarting);
        NeoForge.EVENT_BUS.addListener(this::serverStarted);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::afterServerTick);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        FixtureSelection selection = fixtureSelection();
        if (selection.ordinaryPhase != null || selection.safeDeleteCrashPhase != null) return; // ordinary server profiles, never a GameTest selection.
        String restartPhase = selection.seededRestartPhase;
        event.register(selection.seededBridgeRegression ? SeededBridgeRegressionGameTest.class : switch (restartPhase) {
            case "A" -> ProviderSeededRestartPhaseAGameTest.class;
            case "B" -> ProviderSeededRestartPhaseBGameTest.class;
            default -> selection.providerCreateIntegration
                    ? (Boolean.getBoolean("dynamicverse.explicitSuperflat") ? ProviderExplicitSuperflatGameTest.class : ProviderCreateIntegrationGameTest.class)
                    : selection.providerSeededDuplicateIntegration
                            ? ProviderSeededDuplicateIntegrationGameTest.class
                    : selection.providerBridgeIntegration
                            ? ProviderBridgeIntegrationGameTest.class : ProviderDuplicationBridgeGameTest.class;
        });
    }

    /** The two-phase native fixture owns its explicit Runtime instance so phase B can inspect durable state first. */
    private void serverStarting(ServerStartingEvent event) {
        FixtureSelection selection = fixtureSelection();
        if (selection.ordinaryPhase != null) ProviderOrdinaryRestartFixture.validateBeforeRuntime(selection.ordinaryPhase, event.getServer());
        if (!selection.ownsRuntime()) runtime = ServerVerseRuntime.create(event.getServer());
    }
    private void registerCommands(RegisterCommandsEvent event) {
        NeoForgeVerseCommands.register(event.getDispatcher(), facade, lease -> runtime == null ? null : runtime.resolve(lease), facade);
    }
    /** Test-only ordinary-server hook; inactive unless an O1/O2 run sets the property. */
    private void serverStarted(ServerStartedEvent event) {
        FixtureSelection selection = fixtureSelection();
        if (selection.ordinaryPhase != null && runtime != null) {
            ordinaryRestart = new ProviderOrdinaryRestartFixture(selection.ordinaryPhase);
            ordinaryRestart.started(event.getServer(), runtime);
        }
        // Prepared for review, not yet executed: see
        // docs/real-provider-safe-delete-acceptance-plan-20261104.md. Phase A
        // deliberately blocks the server thread here and never returns.
        if (selection.safeDeleteCrashPhase != null && runtime != null) {
            safeDeleteCrash = new ProviderSafeDeleteCrashFixture(selection.safeDeleteCrashPhase);
            safeDeleteCrash.started(event.getServer(), runtime);
        }
    }
    private void afterServerTick(ServerTickEvent.Post event) {
        if (runtime != null && runtime.owns(event.getServer())) runtime.tick();
        if (ordinaryRestart != null) ordinaryRestart.tick();
    }
    private void serverStopping(ServerStoppingEvent event) {
        ordinaryRestart = null;
        safeDeleteCrash = null;
        if (runtime != null && runtime.owns(event.getServer())) { runtime.close(); runtime = null; }
    }

    /** One property family may select a fixture; mixed GameTest/A/B/O1/O2 runs fail before any Runtime is created. */
    private static FixtureSelection fixtureSelection() {
        String seededPhase = System.getProperty("dynamicverse.providerSeededRestartPhase", "");
        String ordinaryPhase = System.getProperty("dynamicverse.providerOrdinaryRestartPhase", "");
        String safeDeleteCrashPhase = System.getProperty("dynamicverse.providerSafeDeleteCrashPhase", "");
        boolean seededBridge = Boolean.getBoolean("dynamicverse.seededBridgeRegression");
        boolean providerCreate = Boolean.getBoolean("dynamicverse.providerCreateIntegration");
        boolean providerDuplicate = Boolean.getBoolean("dynamicverse.providerSeededDuplicateIntegration");
        boolean providerBridge = Boolean.getBoolean("dynamicverse.providerBridgeIntegration");
        if (!seededPhase.isEmpty() && !seededPhase.equals("A") && !seededPhase.equals("B")) throw new IllegalStateException("unsupported seeded restart phase");
        if (!ordinaryPhase.isEmpty() && !ordinaryPhase.equals("O1") && !ordinaryPhase.equals("O2")) throw new IllegalStateException("unsupported ordinary restart phase");
        if (!safeDeleteCrashPhase.isEmpty() && !safeDeleteCrashPhase.equals("A") && !safeDeleteCrashPhase.equals("B")) throw new IllegalStateException("unsupported safe-delete crash phase");
        int selected = (seededPhase.isEmpty() ? 0 : 1) + (ordinaryPhase.isEmpty() ? 0 : 1) + (seededBridge ? 1 : 0)
                + (providerCreate ? 1 : 0) + (providerDuplicate ? 1 : 0) + (providerBridge ? 1 : 0) + (safeDeleteCrashPhase.isEmpty() ? 0 : 1);
        if (selected > 1) throw new IllegalStateException("mixed Dynamic Verse fixture selectors are refused");
        return new FixtureSelection(seededPhase, ordinaryPhase.isEmpty() ? null : ordinaryPhase, seededBridge, providerCreate, providerDuplicate, providerBridge,
                safeDeleteCrashPhase.isEmpty() ? null : safeDeleteCrashPhase);
    }
    private record FixtureSelection(String seededRestartPhase, String ordinaryPhase, boolean seededBridgeRegression,
                                    boolean providerCreateIntegration, boolean providerSeededDuplicateIntegration,
                                    boolean providerBridgeIntegration, String safeDeleteCrashPhase) {
        boolean ownsRuntime() { return seededRestartPhase.equals("A") || seededRestartPhase.equals("B") || providerSeededDuplicateIntegration; }
    }

    /** Stateless delegation prevents command-event ordering from fabricating a runtime/catalog. */
    private final class BootstrapFacade implements VerseAccessService, VerseLifecycleCommandService {
        @Override public VerseReservationSnapshot beginAcquireReadyRead(String name, VerseCompletionSink<VerseReservationSnapshot> sink) {
            if (runtime != null) return runtime.beginAcquireReadyRead(name, sink);
            VerseReservationSnapshot failed = VerseReservationSnapshot.failed(VerseReservationId.newId(), name, VerseAccessError.ERR_VERSE_NOT_FOUND);
            sink.complete(failed);
            return failed;
        }
        @Override public DuplicateAdmission beginDuplicate(String source, String destination, Consumer<DuplicateOutcome> completion) {
            return runtime == null ? DuplicateAdmission.ERR_PROVIDER_UNAVAILABLE : runtime.beginDuplicate(source, destination, completion);
        }
        @Override public CreateAdmission beginCreate(String name, CreateType type, OptionalLong seed, Consumer<CreateOutcome> completion) {
            return runtime == null ? CreateAdmission.ERR_PROVIDER_UNAVAILABLE : runtime.beginCreate(name, type, seed, completion);
        }
        @Override public DeleteAdmission beginConfirmedDelete(String name, java.util.function.Consumer<DeleteOutcome> completion) {
            return runtime == null ? DeleteAdmission.ERR_VERSE_NOT_MANAGED : runtime.beginConfirmedDelete(name, completion);
        }
        @Override public DeleteAdmission beginDelete(String name) {
            return runtime == null ? DeleteAdmission.ERR_VERSE_NOT_MANAGED : runtime.beginDelete(name);
        }
        @Override public List<ManagedVerseListing> listManaged() { return runtime == null ? List.of() : runtime.listManaged(); }
    }
}
