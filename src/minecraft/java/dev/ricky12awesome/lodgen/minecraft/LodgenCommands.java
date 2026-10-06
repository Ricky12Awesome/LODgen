package dev.ricky12awesome.lodgen.minecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.task.RadiusParser;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;

import java.util.function.Consumer;

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
                .then(Commands.argument("x", coordinate())
                        .then(Commands.argument("z", coordinate()).then(radius(GenerationCenter.CUSTOM))))
                .then(Commands.literal("origin").then(radius(GenerationCenter.ORIGIN)))
                .then(Commands.literal("current").then(radius(GenerationCenter.CURRENT)))));
        root.then(control("stop", GenerationTasks::stop));
        root.then(control("cancel", GenerationTasks::stop));
        root.then(control("pause", GenerationTasks::pause));
        root.then(control("continue", GenerationTasks::resume));
        root.then(control("status", tasks -> {}));
        dispatcher.register(root);
    }
    private static IntegerArgumentType coordinate() {
        return IntegerArgumentType.integer(-GenerationArea.WORLD_EDGE_BLOCKS, GenerationArea.WORLD_EDGE_BLOCKS);
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> radius(GenerationCenter center) {
        return Commands.argument("radius", StringArgumentType.word())
                .executes(context -> start(context, center, "0"))
                .then(Commands.argument("saved-radius", StringArgumentType.word())
                        .executes(context -> start(context, center, StringArgumentType.getString(context, "saved-radius"))));
    }
    private static LiteralArgumentBuilder<CommandSourceStack> control(String name, Consumer<GenerationTasks> action) {
        return Commands.literal(name).executes(context -> control(context, action));
    }
    private static int start(CommandContext<CommandSourceStack> context, GenerationCenter center, String saved) throws CommandSyntaxException {
        var source = context.getSource();
        var level = DimensionArgument.getDimension(context, "dim");
        int x, z;
        if (center == GenerationCenter.CUSTOM) {
            x = IntegerArgumentType.getInteger(context, "x"); z = IntegerArgumentType.getInteger(context, "z");
        } else if (center == GenerationCenter.ORIGIN) {
            var spawn = GenerationCenters.spawn(level); x = spawn.getX(); z = spawn.getZ();
        } else {
            var player = source.getPlayerOrException();
            if (player.level() != level) throw new SimpleCommandExceptionType(CommandText.message("lodgen.command.error.player_dimension_mismatch")).create();
            var pos = player.blockPosition(); x = pos.getX(); z = pos.getZ();
        }
        try {
            var area = new GenerationArea(x, z, RadiusParser.chunks(StringArgumentType.getString(context, "radius")), RadiusParser.chunks(saved));
            if (area.radius() < 1) throw new IllegalArgumentException("lodgen.command.error.radius_too_small");
            var tasks = GenerationTasks.get(source.getServer());
            tasks.start(level, area);
            source.sendSuccess(tasks::statusMessage, false);
            return 1;
        } catch (IllegalArgumentException | IllegalStateException error) { throw new SimpleCommandExceptionType(CommandText.error(error)).create(); }
    }
    private static int control(CommandContext<CommandSourceStack> context, Consumer<GenerationTasks> action) throws CommandSyntaxException {
        var source = context.getSource();
        var tasks = GenerationTasks.get(source.getServer());
        try {
            action.accept(tasks);
            source.sendSuccess(tasks::statusMessage, false);
            return 1;
        } catch (IllegalStateException error) { throw new SimpleCommandExceptionType(CommandText.error(error)).create(); }
    }
}
