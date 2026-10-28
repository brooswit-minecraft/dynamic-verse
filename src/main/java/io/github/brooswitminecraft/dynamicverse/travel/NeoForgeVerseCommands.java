package io.github.brooswitminecraft.dynamicverse.travel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseAccessService;
import io.github.brooswitminecraft.dynamicverse.lifecycle.ManagedVerseListing;
import io.github.brooswitminecraft.dynamicverse.lifecycle.VerseLifecycleCommandService;
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
    private static final String DUPLICATE = "duplicate";
    private static final String CREATE = "create";
    private static final String LIST = "list";
    private static final String SOURCE = "source";
    private static final String NAME = "name";
    private static final String PLAYER = "player";
    private static final String POSITION = "position";
    private static final String TYPE = "type";
    private static final String SEED = "seed";

    private NeoForgeVerseCommands() { }

    public static void register(
            RegisterCommandsEvent event, VerseAccessService lifecycle, NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        register(event.getDispatcher(), lifecycle, levels);
    }

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            VerseAccessService lifecycle,
            NeoForgeVerseTravelAdapter.ServerLevelResolver levels) {
        register(dispatcher, lifecycle, levels, null);
    }

    /** Extends the single /verse tree; null lifecycle commands retain travel-only test registration. */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, VerseAccessService lifecycle,
                                NeoForgeVerseTravelAdapter.ServerLevelResolver levels, VerseLifecycleCommandService commands) {
        Objects.requireNonNull(dispatcher);
        Objects.requireNonNull(lifecycle);
        Objects.requireNonNull(levels);
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(VERSE)
                .requires(source -> source.hasPermission(OPERATOR_PERMISSION_LEVEL))
                .then(tp(lifecycle, levels))
                .then(send(lifecycle, levels))
                .then(bring(lifecycle, levels));
        if (commands != null) root.then(duplicate(commands)).then(create(commands)).then(list(commands));
        dispatcher.register(root);
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

    private static LiteralArgumentBuilder<CommandSourceStack> duplicate(VerseLifecycleCommandService lifecycle) {
        return Commands.literal(DUPLICATE).then(Commands.argument(SOURCE, StringArgumentType.word())
                .then(Commands.argument(NAME, StringArgumentType.word()).executes(context -> {
                    CommandSourceStack source = context.getSource();
                    boolean[] admitted = {false};
                    java.util.List<VerseLifecycleCommandService.DuplicateOutcome> early = new java.util.ArrayList<>();
                    java.util.concurrent.atomic.AtomicBoolean terminalOnce = new java.util.concurrent.atomic.AtomicBoolean();
                    VerseLifecycleCommandService.DuplicateAdmission result = lifecycle.beginDuplicate(
                            StringArgumentType.getString(context, SOURCE), StringArgumentType.getString(context, NAME), outcome -> {
                                if (!terminalOnce.compareAndSet(false, true)) return;
                                if (admitted[0]) duplicateTerminal(source, outcome); else early.add(outcome);
                            });
                    if (result != VerseLifecycleCommandService.DuplicateAdmission.ADMITTED) {
                        source.sendFailure(Component.literal("duplicate failed: " + result));
                        return 0;
                    }
                    admitted[0] = true;
                    source.sendSuccess(() -> Component.literal("duplicate accepted/pending"), false);
                    early.forEach(outcome -> duplicateTerminal(source, outcome));
                    return 1;
                })));
    }

    private static void duplicateTerminal(CommandSourceStack source, VerseLifecycleCommandService.DuplicateOutcome outcome) {
        if (outcome == VerseLifecycleCommandService.DuplicateOutcome.LOADED) source.sendSuccess(() -> Component.literal("duplicate loaded"), false);
        else source.sendFailure(Component.literal("duplicate failed: " + outcome));
    }

    /** Accepted grammar: /verse create <name> <normal|superflat> [seed]. */
    private static LiteralArgumentBuilder<CommandSourceStack> create(VerseLifecycleCommandService lifecycle) {
        return Commands.literal(CREATE).then(Commands.argument(NAME, StringArgumentType.word())
                .then(Commands.argument(TYPE, StringArgumentType.word())
                        .executes(context -> create(context, lifecycle, java.util.OptionalLong.empty()))
                        .then(Commands.argument(SEED, LongArgumentType.longArg())
                                .executes(context -> create(context, lifecycle,
                                        java.util.OptionalLong.of(LongArgumentType.getLong(context, SEED)))))));
    }
    private static int create(CommandContext<CommandSourceStack> context, VerseLifecycleCommandService lifecycle,
                              java.util.OptionalLong seed) {
        CommandSourceStack source = context.getSource();
        VerseLifecycleCommandService.CreateType type;
        try { type = VerseLifecycleCommandService.CreateType.valueOf(StringArgumentType.getString(context, TYPE).toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException badType) { source.sendFailure(Component.literal("create failed: ERR_INVALID_NAME_OR_TYPE")); return 0; }
        boolean[] admitted = {false};
        java.util.List<VerseLifecycleCommandService.CreateOutcome> early = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean terminalOnce = new java.util.concurrent.atomic.AtomicBoolean();
        VerseLifecycleCommandService.CreateAdmission result = lifecycle.beginCreate(StringArgumentType.getString(context, NAME), type, seed, outcome -> {
            if (!terminalOnce.compareAndSet(false, true)) return;
            if (admitted[0]) createTerminal(source, outcome); else early.add(outcome);
        });
        if (result != VerseLifecycleCommandService.CreateAdmission.ADMITTED) {
            source.sendFailure(Component.literal("create failed: " + result));
            return 0;
        }
        admitted[0] = true;
        source.sendSuccess(() -> Component.literal("create accepted/pending"), false);
        early.forEach(outcome -> createTerminal(source, outcome));
        return 1;
    }
    private static void createTerminal(CommandSourceStack source, VerseLifecycleCommandService.CreateOutcome outcome) {
        if (outcome == VerseLifecycleCommandService.CreateOutcome.READY) source.sendSuccess(() -> Component.literal("create ready"), false);
        else source.sendFailure(Component.literal("create failed: " + outcome));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> list(VerseLifecycleCommandService lifecycle) {
        return Commands.literal(LIST).executes(context -> {
            java.util.List<ManagedVerseListing> managed = lifecycle.listManaged();
            String message = managed.isEmpty() ? "managed verses: none" : "managed verses: " + managed.stream()
                    .map(entry -> entry.name() + "=" + entry.state()).collect(java.util.stream.Collectors.joining(", "));
            context.getSource().sendSuccess(() -> Component.literal(message), false);
            return managed.size();
        });
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
