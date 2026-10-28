package io.github.brooswitminecraft.dynamicverse.travel;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Objects;

/** Package-visible command-context seam used by the NeoForge registrar's disposable fixtures. */
public final class TravelCommandFixtureRegistrar<S> {
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";
    public interface Context<S> {
        boolean permitted(S source);
        VerseTravelService.Player caller(S source);
        VerseTravelService.Player player(String name);
        VerseTravelService travel(S source);
        void success(S source, String message);
        void failure(S source, String message);
    }
    private final Context<S> context;
    private final VerseTravelCommandExecutor<S> executor;
    public TravelCommandFixtureRegistrar(Context<S> context) {
        this.context = Objects.requireNonNull(context);
        this.executor = new VerseTravelCommandExecutor<>(new VerseTravelCommandExecutor.Context<>() {
            @Override public VerseTravelService travel(S source) { return context.travel(source); }
            @Override public void success(S source, String message) { context.success(source, message); }
            @Override public void failure(S source, String message) { context.failure(source, message); }
        });
    }
    public void register(CommandDispatcher<S> dispatcher) {
        var root = LiteralArgumentBuilder.<S>literal("verse").requires(context::permitted);
        root.then(LiteralArgumentBuilder.<S>literal("bring").then(
                com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                        .executes(c -> bring(c.getSource(), StringArgumentType.getString(c, "player")))));
        var tpName = com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                .executes(c -> tp(c.getSource(), StringArgumentType.getString(c, "name"), null));
        tpName.then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Double>argument(X, DoubleArgumentType.doubleArg())
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Double>argument(Y, DoubleArgumentType.doubleArg())
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Double>argument(Z, DoubleArgumentType.doubleArg())
                                .executes(c -> tp(c.getSource(), StringArgumentType.getString(c, "name"), position(c))))));
        root.then(LiteralArgumentBuilder.<S>literal("tp").then(tpName));
        var sendPlayer = com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word());
        var sendName = com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.word())
                .executes(c -> send(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "name"), null));
        sendName.then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Double>argument(X, DoubleArgumentType.doubleArg())
                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Double>argument(Y, DoubleArgumentType.doubleArg())
                        .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.<S, Double>argument(Z, DoubleArgumentType.doubleArg())
                                .executes(c -> send(c.getSource(), StringArgumentType.getString(c, "player"), StringArgumentType.getString(c, "name"), position(c))))));
        sendPlayer.then(sendName);
        root.then(LiteralArgumentBuilder.<S>literal("send").then(sendPlayer));
        dispatcher.register(root);
    }
    private int bring(S source, String player) {
        VerseTravelService.Player caller = context.caller(source), target = context.player(player);
        return executor.bring(source, caller, target);
    }
    private int send(S source, String player, String name, VerseTravelService.Position requested) {
        VerseTravelService.Player target = context.player(player);
        return executor.send(source, target, name, requested);
    }
    private int tp(S source, String name, VerseTravelService.Position requested) {
        VerseTravelService.Player caller = context.caller(source);
        return executor.tp(source, caller, name, requested);
    }
    private static <S> VerseTravelService.Position position(com.mojang.brigadier.context.CommandContext<S> context) {
        return new VerseTravelService.Position(DoubleArgumentType.getDouble(context, X), DoubleArgumentType.getDouble(context, Y), DoubleArgumentType.getDouble(context, Z));
    }
}
