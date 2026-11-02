package io.github.brooswitminecraft.dynamicverse.travel;

import io.github.brooswitminecraft.dynamicverse.lifecycle.ReadyVerseLease;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.RelativeMovement;
import java.util.Set;

/** Concrete ServerPlayer/ServerLevel bridge; the lifecycle module owns lease production. */
public final class NeoForgeVerseTravelAdapter {
    private NeoForgeVerseTravelAdapter() { }

    public static VerseTravelService create(
            VerseAccessService lifecycle, MinecraftServer server, ServerLevelResolver levels) {
        return new VerseTravelService(lifecycle, new Players(server), new Movement(server, levels));
    }

    @FunctionalInterface
    public interface ServerLevelResolver {
        /** Resolves only the ServerLevel identity already acquired under the lifecycle lease. */
        ServerLevel resolve(ReadyVerseLease lease);
    }

    public static VerseTravelService.Player player(ServerPlayer player) {
        return new VerseTravelService.Player(player.getUUID(), new VerseTravelService.Position(
                player.getX(), player.getY(), player.getZ()));
    }

    /** @deprecated use the shared command/movement bounds in {@link TravelCoordinates}. */
    @Deprecated
    public static boolean isValidTeleportPosition(VerseTravelService.Position position) {
        return TravelCoordinates.isValid(position);
    }

    private static final class Players implements VerseTravelService.PlayerDirectory {
        private final MinecraftServer server;
        private Players(MinecraftServer server) { this.server = server; }
        @Override public VerseTravelService.Player online(UUID id) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            return player == null ? null : NeoForgeVerseTravelAdapter.player(player);
        }
        @Override public void runLater(Runnable continuation) { server.execute(continuation); }
    }

    private static final class Movement implements VerseTravelService.Movement {
        private final MinecraftServer server;
        private final ServerLevelResolver levels;
        private Movement(MinecraftServer server, ServerLevelResolver levels) {
            this.server = server;
            this.levels = levels;
        }
        @Override public void move(VerseTravelService.Player target, VerseTravelService.Position requested, ReadyVerseLease lease) {
            ServerPlayer player = requireOnline(target.id());
            ServerLevel level = levels.resolve(lease);
            if (level == null) throw new IllegalStateException("acquired level disappeared");
            VerseTravelService.Position destination = requested == null
                    ? new VerseTravelService.Position(level.getSharedSpawnPos().getX(), level.getSharedSpawnPos().getY(), level.getSharedSpawnPos().getZ())
                    : requested;
            validate(destination);
            if (!player.teleportTo(level, destination.x(), destination.y(), destination.z(), Set.<RelativeMovement>of(), player.getYRot(), player.getXRot())) {
                throw new IllegalStateException("teleport was canceled or did not complete");
            }
        }
        @Override public void bring(VerseTravelService.Player target, VerseTravelService.Player caller) {
            ServerPlayer targetPlayer = requireOnline(target.id());
            ServerPlayer callerPlayer = requireOnline(caller.id());
            VerseTravelService.Position destination = new VerseTravelService.Position(callerPlayer.getX(), callerPlayer.getY(), callerPlayer.getZ());
            validate(destination);
            if (!targetPlayer.teleportTo(callerPlayer.serverLevel(), destination.x(), destination.y(), destination.z(), Set.<RelativeMovement>of(), callerPlayer.getYRot(), callerPlayer.getXRot())) {
                throw new IllegalStateException("teleport was canceled or did not complete");
            }
        }
        private ServerPlayer requireOnline(UUID id) {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) throw new IllegalStateException("player went offline");
            return player;
        }
        private static void validate(VerseTravelService.Position destination) {
            if (!TravelCoordinates.isValid(destination)) throw new VerseTravelService.InvalidCoordinatesException();
        }
    }
}
