package dev.ricky12awesome.lodgen.minecraft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/** Minecraft's dimension identifier and save layout, isolated from generation policy. */
final class WorldPaths {
    private WorldPaths() {}

    static String dimension(ServerLevel level) {
        // #if MC_1211
        return level.dimension().location().toString();
        // #else
        return level.dimension().identifier().toString();
        // #endif
    }

    static Path dimensionDirectory(ServerLevel level) {
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        // #if MC_1211
        if (level.dimension() == Level.OVERWORLD) return root;
        if (level.dimension() == Level.NETHER) return root.resolve("DIM-1");
        if (level.dimension() == Level.END) return root.resolve("DIM1");
        var id = level.dimension().location();
        // #else
        var id = level.dimension().identifier();
        // #endif
        return root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
    }
}
