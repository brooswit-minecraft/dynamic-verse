package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;

import java.util.Objects;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.function.Consumer;

/** Actual pinned-provider adapter. It uses only DynamicDimensionRegistry's public close/load methods. */
public final class MinecraftProviderDuplicationPort implements ProviderDuplicationPort {
    private final DynamicDimensionRegistry registry;
    private final MinecraftServer server;
    private final RegistrationObservationQueue observations = new RegistrationObservationQueue();
    private final EventOwner eventOwner = new EventOwner();

    public MinecraftProviderDuplicationPort(MinecraftServer server) {
        this.server = Objects.requireNonNull(server);
        this.registry = DynamicDimensionRegistry.from(this.server);
        NeoForge.EVENT_BUS.register(eventOwner);
    }

    @Override
    public boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, Consumer<ProviderQuiesceCloseTerminal> terminal) {
        ServerLevel level = server.getLevel(source.key());
        if (level == null || !level.players().isEmpty()) return false;
        return registry.beginQuiesceAndClose(source.key().location(), (MinecraftServer server, ServerPlayer player) -> {
                    throw new IllegalStateException("Populated source is unsupported by the empty-source-only duplication path");
                },
                result -> terminal.accept(map(result)));
    }

    @Override
    public RestoredSource loadOriginalSource(OwnedDimensionDescriptor source, long restorationGeneration) {
        ServerLevel restored = registry.loadDynamicDimension(source.key().location(), source.generator(), source.type(), source.properties());
        return restored == null ? null : new RestoredSource(restored, identity(restored), restorationGeneration);
    }

    @Override
    public LoadedDestination loadCopiedDestination(OwnedDimensionDescriptor destination, long generation) {
        ServerLevel loaded = registry.loadDynamicDimension(destination.key().location(), destination.generator(), destination.type(), destination.properties());
        return loaded == null ? null : new LoadedDestination(loaded, identity(loaded), generation);
    }

    @Override
    public boolean isCurrent(OwnedDimensionDescriptor descriptor, Object instance) {
        return instance instanceof ServerLevel level && server.getLevel(descriptor.key()) == level;
    }

    @Override
    public boolean isAbsent(OwnedDimensionDescriptor descriptor) {
        return server.getLevel(descriptor.key()) == null;
    }

    @Override
    public boolean hasCopiedDestination(ClosedDimensionCopyRequest request) {
        return Files.isDirectory(request.storageRoot().resolve(request.destinationRelative()).normalize(), LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public void awaitCurrent(OwnedDimensionDescriptor descriptor, Object instance, Consumer<Boolean> completion) {
        observations.await(() -> isCurrent(descriptor, instance), completion);
    }

    private final class EventOwner {
        @SubscribeEvent
        public void afterServerTick(ServerTickEvent.Post event) {
            if (event.getServer() == server) observations.observeTick();
        }
        @SubscribeEvent
        public void serverStopping(ServerStoppingEvent event) {
            if (event.getServer() == server) {
                observations.retire();
                NeoForge.EVENT_BUS.unregister(this);
            }
        }
    }

    private static String identity(ServerLevel level) {
        return level.dimension().location() + "@" + Integer.toHexString(System.identityHashCode(level));
    }

    private static ProviderQuiesceCloseTerminal map(QuiesceAndCloseResult result) {
        ClosedDimensionStatus status = switch (result.status()) {
            case SAVED_AND_CLOSED -> ClosedDimensionStatus.SAVED_AND_CLOSED;
            case FAILED_SAVE -> ClosedDimensionStatus.FAILED_SAVE;
            case FAILED_CLOSE -> ClosedDimensionStatus.FAILED_CLOSE;
        };
        return new ProviderQuiesceCloseTerminal(result.key().location().toString(), result.generation(), result.initiallyLoaded(),
                status, result.failureStage() != null || result.cause() != null,
                result.failureStage() == null ? null : result.failureStage().name(), result.cause());
    }
}
