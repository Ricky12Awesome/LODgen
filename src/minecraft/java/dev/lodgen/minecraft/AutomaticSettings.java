package dev.lodgen.minecraft;

import dev.lodgen.GenerationSettings;
import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.CaveMode;
import dev.lodgen.generation.GenerationCenter;
import dev.lodgen.task.TaskRecord;
import net.minecraft.server.level.ServerLevel;

/** Settings that determine whether an automatic job must be replaced. */
record AutomaticSettings(GenerationCenter center, int x, int z, int radius, int savedRadius, CaveMode caveMode) {
    static int displayRadius(ServerLevel level) {
        return displayRadius(level, LodgenConfig.INSTANCE);
    }

    private static int displayRadius(ServerLevel level, LodgenConfig config) {
        return config.generationDistance() > 0 ? config.generationDistance()
                : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
    }

    static AutomaticSettings current(ServerLevel level) {
        var config = LodgenConfig.INSTANCE;
        var center = GenerationCenters.resolve(level, config, 0, 0);
        boolean lods = RendererSinks.dhAvailable() && GenerationSettings.policy().features()
                || RendererSinks.voxyAvailable() && !level.getServer().isDedicatedServer();
        int radius = lods ? displayRadius(level, config) : config.savedChunkRadius();
        return new AutomaticSettings(config.generationCenter(), center.getX(), center.getZ(), radius, config.savedChunkRadius(), config.caveMode());
    }

    static AutomaticSettings restored(ServerLevel level, TaskRecord record) {
        var live = current(level);
        if (!record.automatic()) return live;
        return new AutomaticSettings(live.center(), live.center() == GenerationCenter.CURRENT ? 0 : record.area().blockX(),
                live.center() == GenerationCenter.CURRENT ? 0 : record.area().blockZ(),
                record.area().radius(), record.area().savedRadius(), record.caveMode());
    }
}
