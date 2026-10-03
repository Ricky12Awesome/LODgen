package dev.lodgen.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.lodgen.generation.GenerationArea;
import dev.lodgen.task.RadiusParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.network.chat.Component;

/** Shared Brigadier tree for both loaders; centers are horizontal block coordinates. */
public final class LodgenCommands {
    private LodgenCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("lodgen")
                // #if MC_1211
                .requires(source -> source.hasPermission(2));
                // #else
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
                // #endif
        root.then(Commands.literal("start").then(Commands.argument("dim", DimensionArgument.dimension())
                .then(Commands.argument("x", IntegerArgumentType.integer(-30_000_000, 30_000_000))
                        .then(Commands.argument("z", IntegerArgumentType.integer(-30_000_000, 30_000_000)).then(radius("custom"))))
                .then(Commands.literal("origin").then(radius("origin")))
                .then(Commands.literal("current").then(radius("current")))));
        root.then(Commands.literal("stop").executes(context -> control(context, "stop")));
        root.then(Commands.literal("cancel").executes(context -> control(context, "stop")));
        root.then(Commands.literal("pause").executes(context -> control(context, "pause")));
        root.then(Commands.literal("continue").executes(context -> control(context, "continue")));
        root.then(Commands.literal("status").executes(context -> control(context, "status")));
        dispatcher.register(root);
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> radius(String center) {
        return Commands.argument("radius", StringArgumentType.word())
                .executes(context -> start(context, center, "0"))
                .then(Commands.argument("saved-radius", StringArgumentType.word())
                        .executes(context -> start(context, center, StringArgumentType.getString(context, "saved-radius"))));
    }
    private static int start(CommandContext<CommandSourceStack> context, String center, String saved) throws CommandSyntaxException {
        var source = context.getSource();
        var level = DimensionArgument.getDimension(context, "dim");
        int x, z;
        if (center.equals("custom")) {
            x = IntegerArgumentType.getInteger(context, "x"); z = IntegerArgumentType.getInteger(context, "z");
        } else if (center.equals("origin")) {
            var spawn = GenerationCenters.spawn(level); x = spawn.getX(); z = spawn.getZ();
        } else {
            var player = source.getPlayerOrException();
            if (player.level() != level) throw new SimpleCommandExceptionType(Component.literal("current requires the player to be in the selected dimension")).create();
            var pos = player.blockPosition(); x = pos.getX(); z = pos.getZ();
        }
        try {
            var area = new GenerationArea(x, z, RadiusParser.chunks(StringArgumentType.getString(context, "radius")), RadiusParser.chunks(saved));
            if (area.radius() < 1) throw new IllegalArgumentException("Generation radius must be at least one chunk");
            var tasks = GenerationTasks.get(source.getServer());
            tasks.start(level, area);
            source.sendSuccess(() -> Component.literal(tasks.status()), false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { throw new SimpleCommandExceptionType(Component.literal(error.getMessage())).create(); }
    }
    private static int control(CommandContext<CommandSourceStack> context, String action) throws CommandSyntaxException {
        var source = context.getSource();
        var tasks = GenerationTasks.get(source.getServer());
        try {
            switch (action) { case "stop" -> tasks.stop(); case "pause" -> tasks.pause(); case "continue" -> tasks.resume(); }
            source.sendSuccess(() -> Component.literal(tasks.status()), false);
            return 1;
        } catch (IllegalStateException error) { throw new SimpleCommandExceptionType(Component.literal(error.getMessage())).create(); }
    }
}
