package dev.lodgen.startup;

import dev.lodgen.LodgenConfig;
import dev.lodgen.ModSupport;
import java.nio.file.Files;
import java.nio.file.Path;

/** Force the generation targets through the mixin transformer without opening a world. */
public final class StartupCheck {
    private StartupCheck() {}

    public static void checkTargets() throws Exception {
        Class<?>[] minecraftTargets = {
                net.minecraft.server.level.ServerLevel.class,
                net.minecraft.commands.Commands.class,
                net.minecraft.server.MinecraftServer.class,
                net.minecraft.server.level.ServerChunkCache.class,
                net.minecraft.server.level.ChunkMap.class,
                // #if MC_1211
                net.minecraft.server.level.DistanceManager.class,
                // #else
                net.minecraft.world.level.TicketStorage.class,
                // #endif
                net.minecraft.world.level.chunk.ChunkAccess.class,
                net.minecraft.world.level.chunk.ChunkGenerator.class,
                net.minecraft.world.level.chunk.storage.IOWorker.class,
                net.minecraft.world.level.chunk.storage.RegionFileStorage.class
        };
        for (Class<?> target : minecraftTargets) Class.forName(target.getName(), false, target.getClassLoader());
        String[] targets = {
                "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread",
                "com.seibel.distanthorizons.core.api.internal.SharedApi",
                "com.seibel.distanthorizons.core.generation.DhWorldGenerator",
                "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue",
                "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue$TaskDistancePair"
        };
        for (String target : targets) {
            if (target.startsWith("com.seibel.") && !ModSupport.loaded("distanthorizons")) continue;
            Class.forName(target, false, StartupCheck.class.getClassLoader());
        }
        if (!LodgenConfig.INSTANCE.enabled() && !Boolean.getBoolean("lodgen.test.voxy")) throw new AssertionError("Default config is disabled");
        if (!Files.exists(LodgenConfig.FILE)) throw new AssertionError("TOML config missing");
    }

    public static void report(String result) {
        try { Files.writeString(Path.of("startup-result.txt"), result + "\n"); }
        catch (Exception failure) { throw new RuntimeException(failure); }
        LodgenConfig.LOGGER.info(result);
    }
}
