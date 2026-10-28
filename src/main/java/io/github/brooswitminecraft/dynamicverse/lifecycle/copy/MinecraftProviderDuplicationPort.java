package io.github.brooswitminecraft.dynamicverse.lifecycle.copy;

import dev.galacticraft.dynamicdimensions.api.DynamicDimensionRegistry;
import dev.galacticraft.dynamicdimensions.api.lifecycle.QuiesceAndCloseResult;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;
import java.util.function.Consumer;

/** Actual pinned-provider adapter. It uses only DynamicDimensionRegistry's public close/load methods. */
public final class MinecraftProviderDuplicationPort implements ProviderDuplicationPort {
    private final DynamicDimensionRegistry registry;

    public MinecraftProviderDuplicationPort(MinecraftServer server) {
        this.registry = DynamicDimensionRegistry.from(Objects.requireNonNull(server));
    }

    @Override
    public boolean beginQuiesceAndClose(OwnedDimensionDescriptor source, Consumer<ProviderQuiesceCloseTerminal> terminal) {
        return registry.beginQuiesceAndClose(source.key().location(), (MinecraftServer server, ServerPlayer player) -> { },
                result -> terminal.accept(map(result)));
    }

    @Override
    public boolean loadOriginalSource(OwnedDimensionDescriptor source) {
        ServerLevel restored = registry.loadDynamicDimension(source.key().location(), source.generator(), source.type(), source.properties());
        return restored != null;
    }

    private static ProviderQuiesceCloseTerminal map(QuiesceAndCloseResult result) {
        ClosedDimensionStatus status = switch (result.status()) {
            case SAVED_AND_CLOSED -> ClosedDimensionStatus.SAVED_AND_CLOSED;
            case FAILED_SAVE -> ClosedDimensionStatus.FAILED_SAVE;
            case FAILED_CLOSE -> ClosedDimensionStatus.FAILED_CLOSE;
        };
        return new ProviderQuiesceCloseTerminal(result.key().location().toString(), result.generation(), result.initiallyLoaded(),
                status, result.failureStage() != null || result.cause() != null);
    }
}
