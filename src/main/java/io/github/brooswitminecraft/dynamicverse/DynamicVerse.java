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
import io.github.brooswitminecraft.dynamicverse.lifecycle.copy.ProviderBridgeIntegrationGameTest;
import java.util.List;
import java.util.function.Consumer;

/** Server bootstrap owns one runtime facade and the existing GameTest registrations. */
@Mod(DynamicVerse.MOD_ID)
public final class DynamicVerse {
    public static final String MOD_ID = "dynamicverse";
    private ServerVerseRuntime runtime;
    private final BootstrapFacade facade = new BootstrapFacade();

    public DynamicVerse(IEventBus modEventBus) {
        modEventBus.addListener(this::registerGameTests);
        NeoForge.EVENT_BUS.addListener(this::serverStarting);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::afterServerTick);
        NeoForge.EVENT_BUS.addListener(this::serverStopping);
    }

    private void registerGameTests(RegisterGameTestsEvent event) {
        event.register(Boolean.getBoolean("dynamicverse.providerBridgeIntegration")
                ? ProviderBridgeIntegrationGameTest.class : ProviderDuplicationBridgeGameTest.class);
    }

    private void serverStarting(ServerStartingEvent event) { runtime = ServerVerseRuntime.create(event.getServer()); }
    private void registerCommands(RegisterCommandsEvent event) {
        NeoForgeVerseCommands.register(event.getDispatcher(), facade, lease -> runtime == null ? null : runtime.resolve(lease), facade);
    }
    private void afterServerTick(ServerTickEvent.Post event) { if (runtime != null && runtime.owns(event.getServer())) runtime.tick(); }
    private void serverStopping(ServerStoppingEvent event) { if (runtime != null && runtime.owns(event.getServer())) { runtime.close(); runtime = null; } }

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
        @Override public List<ManagedVerseListing> listManaged() { return runtime == null ? List.of() : runtime.listManaged(); }
    }
}
