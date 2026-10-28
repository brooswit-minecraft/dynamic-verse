package io.github.brooswitminecraft.dynamicverse.travel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseReservationSnapshot;
import java.util.Objects;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Registers the travel-owned /verse command surface against the lifecycle lease API. */
public final class NeoForgeVerseCommands {
    private static final int OPERATOR_PERMISSION_LEVEL = 2;
    private static final String VERSE = "verse";
    private static final String TP = "tp";
    private static final String SEND = "send";
    private static final String BRING = "bring";
    private static final String NAME = "name";
    private static final String PLAYER = "player";
    private static final String POSITION = "position";

    private NeoForgeVerseCommands() { }

    public static void register(
            RegisterCommandsEvent event, VerseAccessService lifecycle, NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        register(event.getDispatcher(), lifecycle, levels);
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        Objects.requireNonNull(dispatcher);
        Objects.requireNonNull(lifecycle);
        Objects.requireNonNull(levels);
        dispatcher.register(Commands.literal(VERSE)
                .requires(source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL))
                .then(tp(lifecycle, levels))
                .then(send(lifecycle, levels))
                .then(bring(lifecycle, levels)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> tp(
            VerseAccessService lifecycle, NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        return Commands.literal(TP).then(Commands.argument(NAME, StringArgumentType.word())
                .executes(context -> teleportCaller(context, lifecycle, levels, null))
                .then(Commands.argument(POSITION, Vec3Argument.vec3())
                        .executes(context -> teleportCaller(context, lifecycle, levels, position(context)))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> send(
            VerseAccessService lifecycle, NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        return Commands.literal(SEND).then(Commands.argument(PLAYER, EntityArgument.player())
                .then(Commands.argument(NAME, StringArgumentType.word())
                        .executes(context -> send(context, lifecycle, levels, null))
                        .then(Commands.argument(POSITION, Vec3Argument.vec3())
                                .executes(context -> send(context, lifecycle, levels, position(context))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> bring(
            VerseAccessService lifecycle, NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        return Commands.literal(BRING).then(Commands.argument(PLAYER, EntityArgument.player())
                .executes(context -> bring(context, lifecycle, levels)));
    }

    private static int teleportCaller(
            CommandContext<CommandSourceStack> context, VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels, VerseTravelService.Position requested) throws CommandSyntaxException {
        ServerPlayer caller = context.getSource().getPlayerOrException();
        return executor(lifecycle, levels).tp(context.getSource(), NeoForgeVerseTravelAdapter.player(caller), StringArgumentType.getString(context, NAME), requested);
    }

    private static int send(
            CommandContext<CommandSourceStack> context, VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels, VerseTravelService.Position requested) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, PLAYER);
        return executor(lifecycle, levels).send(context.getSource(), NeoForgeVerseTravelAdapter.player(target), StringArgumentType.getString(context, NAME), requested);
    }

    private static int bring(
            CommandContext<CommandSourceStack> context, VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels) throws CommandSyntaxException {
        ServerPlayer caller = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, PLAYER);
        return executor(lifecycle, levels).bring(context.getSource(), NeoForgeVerseTravelAdapter.player(caller), NeoForgeVerseTravelAdapter.player(target));
    }

    private static VerseTravelCommandExecutor<CommandSourceStack> executor(
            VerseAccessService lifecycle, NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        return new VerseTravelCommandExecutor<>(new VerseTravelCommandExecutor.Context<>() {
            @Override public VerseTravelService travel(CommandSourceStack source) {
                return NeoForgeVerseTravelAdapter.create(lifecycle, source.getServer(), levels);
            }
            @Override public void success(CommandSourceStack source, String message) {
                source.sendSuccess(() -> Component.literal(message), false);
            }
            @Override public void failure(CommandSourceStack source, String message) {
                source.sendFailure(Component.literal(message));
            }
        });
    }

    private static VerseTravelService.Position position(CommandContext<CommandSourceStack> context) {
        Vec3 value = Vec3Argument.getVec3(context, POSITION);
        return new VerseTravelService.Position(value.x, value.y, value.z);
    }

}
