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
        VerseTravelService travel = travel(context, lifecycle, levels);
        VerseReservationSnapshot admission = travel.teleportCaller(
                NeoForgeVerseTravelAdapter.player(caller), StringArgumentType.getString(context, NAME), requested, sink(context));
        accepted(context.getSource(), admission);
        return 1;
    }

    private static int send(
            CommandContext<CommandSourceStack> context, VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels, VerseTravelService.Position requested) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(context, PLAYER);
        VerseTravelService travel = travel(context, lifecycle, levels);
        VerseReservationSnapshot admission = travel.sendPlayer(
                NeoForgeVerseTravelAdapter.player(target), StringArgumentType.getString(context, NAME), requested, sink(context));
        accepted(context.getSource(), admission);
        return 1;
    }

    private static int bring(
            CommandContext<CommandSourceStack> context, VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels) throws CommandSyntaxException {
        ServerPlayer caller = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, PLAYER);
        // This must precede scheduling: server.execute may run inline in a fixture.
        context.getSource().sendSuccess(() -> Component.literal("travel accepted/pending"), false);
        travel(context, lifecycle, levels).bringPlayer(
                NeoForgeVerseTravelAdapter.player(caller), NeoForgeVerseTravelAdapter.player(target), sink(context));
        return 1;
    }

    private static VerseTravelService travel(CommandContext<CommandSourceStack> context, VerseAccessService lifecycle,
                                               NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        return NeoForgeVerseTravelAdapter.create(lifecycle, context.getSource().getServer(), levels);
    }

    private static VerseTravelService.Position position(CommandContext<CommandSourceStack> context) {
        Vec3 value = Vec3Argument.getVec3(context, POSITION);
        return new VerseTravelService.Position(value.x, value.y, value.z);
    }

    private static VerseTravelService.ResultSink sink(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        return new VerseTravelService.ResultSink() {
            @Override public void moved() { source.sendSuccess(() -> Component.literal("travel moved"), false); }
            @Override public void failed(VerseTravelService.TravelError error) {
                source.sendFailure(Component.literal("travel failed: " + error));
            }
        };
    }

    private static void accepted(CommandSourceStack source, VerseReservationSnapshot admission) {
        source.sendSuccess(() -> Component.literal("travel accepted/pending " + admission.id().value()), false);
    }
}
