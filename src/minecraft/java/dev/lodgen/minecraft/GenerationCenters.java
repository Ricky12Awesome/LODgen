package dev.lodgen.minecraft;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationArea;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class GenerationCenters {
    private GenerationCenters() {}
    public static BlockPos spawn(ServerLevel level) {
        // #if MC_1211
        return level.getSharedSpawnPos();
        // #else
        return level.getRespawnData().pos();
        // #endif
    }
    public static BlockPos resolve(ServerLevel level, int currentX, int currentZ) {
        var settings = LodgenConfig.INSTANCE;
        return switch (settings.generationCenter()) {
            case CURRENT -> new BlockPos(currentX, 0, currentZ);
            case ORIGIN -> spawn(level);
            case CUSTOM -> new BlockPos(settings.centerX(), 0, settings.centerZ());
        };
    }
    public static GenerationArea automatic(ServerLevel level, int radius) {
        var players = level.players();
        var fallback = players.isEmpty() ? spawn(level) : players.getFirst().blockPosition();
        var center = resolve(level, fallback.getX(), fallback.getZ());
        return new GenerationArea(center.getX(), center.getZ(), radius, LodgenConfig.INSTANCE.savedChunkRadius());
    }
}
