package dev.ricky12awesome.lodgen.startup;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
import dev.ricky12awesome.lodgen.voxy.VoxyBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Opt-in vanilla Overworld throughput, including real Voxy conversion/storage.
 * Warmup and measured squares are separate; no worldgen steps are disabled.
 */
public final class VoxyBenchmark {
    private static int stage;
    private static long started, sampleTime, previous, baseline, nanos;
    private static CompletableFuture<String> status;
    private static CompletableFuture<NativePregenCheck.Result> chunky;
    private static long chunks;

    public static void start(Minecraft client) throws Exception {
        int load = Integer.getInteger("lodgen.test.cpuLoad", 5);
        LodgenConfig.apply(new LodgenConfig(false, load, 0, false, 1000, GenerationCenter.CURRENT, 0, 0, 0));
        // #if MC_1211
        var settings = new LevelSettings("LODgen Voxy benchmark", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, new net.minecraft.world.level.GameRules(), WorldDataConfiguration.DEFAULT);
        // #else
        var settings = new LevelSettings("LODgen Voxy benchmark", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        // #endif
        client.createWorldOpenFlows().createFreshLevel("lodgen-voxy-benchmark", settings,
                new WorldOptions(123456789L, true, false), registries ->
                // #if MC_1211
                registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                // #else
                registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                // #endif
                new TitleScreen());
    }
    public static void tick(Minecraft client) {
        if (stage == 5 || client.level == null || client.player == null || client.getSingleplayerServer() == null) return;
        try {
            var server = client.getSingleplayerServer();
            var level = server.overworld();
            if (stage < 3 && new VoxyBridge().context(client.level) == null) return;
            var throughput = PersistenceRegistry.throughput(level);
            if (stage == 0) {
                int expected = Integer.getInteger("lodgen.test.nativeWorkers", 0);
                int actual = Class.forName("com.ishland.c2me.base.common.GlobalExecutors").getField("GLOBAL_EXECUTOR_PARALLELISM").getInt(null);
                if (expected > 0 && actual != expected) throw new AssertionError("C2ME parallelism: " + actual + ", expected " + expected);
                status = server.submit(() -> {
                    GenerationTasks.get(server).start(level, new GenerationArea(12064 * 16, -11936 * 16, 64, 0));
                    return GenerationTasks.get(server).status();
                });
                stage = 1; return;
            }
            if (stage == 1 || stage == 2) {
                if (stage == 2 && System.nanoTime() - sampleTime >= 5_000_000_000L) {
                    long done = throughput.totalCompleted() - baseline;
                    LodgenConfig.LOGGER.info("VOXY BENCHMARK: {} / {} chunks; {} chunks/s; heap {} MiB", done, chunks,
                            (done - previous) * 1e9 / (System.nanoTime() - sampleTime),
                            java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576);
                    previous = done; sampleTime = System.nanoTime();
                }
                if (!status.isDone()) return;
                String text = status.join();
                if (text.contains("error=")) throw new AssertionError(text);
                if (!text.contains("COMPLETE")) {
                    status = server.submit(() -> GenerationTasks.get(server).status()); return;
                }
                if (stage == 1) {
                    int radius = Integer.getInteger("lodgen.test.voxyBenchmark", 64);
                    chunks = 4L * radius * radius;
                    baseline = throughput.totalCompleted(); previous = 0;
                    started = sampleTime = System.nanoTime();
                    status = server.submit(() -> {
                        GenerationTasks.get(server).start(level, new GenerationArea((10240 + radius) * 16, (-10240 + radius) * 16, radius, 0));
                        return GenerationTasks.get(server).status();
                    });
                    stage = 2; return;
                }
                nanos = System.nanoTime() - started;
                if (throughput.totalCompleted() - baseline != chunks) throw new AssertionError("Benchmark completion count changed");
                LodgenConfig.LOGGER.info("VOXY BENCHMARK COMPLETE: {} chunks; {} chunks/s", chunks, chunks * 1e9 / nanos);
                // Check real native ownership and Voxy storage before timing the ordinary pregen path.
                if (!PersistenceRegistry.get(level).suppress(10240, -10240)) throw new AssertionError("LOD-only terrain was adopted");
                Object engine = new VoxyBridge().context(level).engine();
                Object section = engine.getClass().getMethod("acquireIfExists", int.class, int.class, int.class, int.class)
                        .invoke(engine, 0, 10240 / 2, 0, -10240 / 2);
                if (section == null) throw new AssertionError("Voxy benchmark did not store LOD data");
                try {
                    if ((int) section.getClass().getMethod("getNonEmptyBlockCount").invoke(section) == 0) throw new AssertionError("Empty Voxy benchmark section");
                } finally { section.getClass().getMethod("release").invoke(section); }
                if (Boolean.getBoolean("lodgen.test.chunkyNativeOnly")) {
                    Class<?> config = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
                    config.getField("ingestEnabled").setBoolean(config.getField("CONFIG").get(null), false);
                }
                chunky = NativePregenCheck.run(16384, -16384, Integer.getInteger("lodgen.test.voxyBenchmark", 64) * 2);
                stage = 3; return;
            }
            if (stage == 3 && chunky.isDone()) {
                var result = chunky.join();
                var budget = dev.ricky12awesome.lodgen.GenerationSettings.current();
                String json = String.format(Locale.ROOT,
                        "{\"voxyChunks\":%d,\"voxySeconds\":%.6f,\"voxyChunksPerSecond\":%.3f,\"chunkyChunks\":%d,\"chunkySeconds\":%.6f,\"chunkyChunksPerSecond\":%.3f,\"nativeWorkers\":%d,\"cpuLoad\":%d,\"conversionWorkers\":%d,\"nativeBatches\":%d,\"vanillaStructures\":true,\"heapBytes\":%d,\"chunkyVoxyIngest\":%s}\n",
                        chunks, nanos / 1e9, chunks * 1e9 / nanos, result.chunks(), result.nanos() / 1e9, result.chunks() * 1e9 / result.nanos(),
                        Integer.getInteger("lodgen.test.nativeWorkers", 0), LodgenConfig.INSTANCE.cpuLoad(), budget.threads(), budget.batches(), Runtime.getRuntime().maxMemory(), !Boolean.getBoolean("lodgen.test.chunkyNativeOnly"));
                Files.writeString(Path.of("benchmark-result.json"), json);
                LodgenConfig.LOGGER.info("VOXY BENCHMARK RESULT: {}", json.trim());
                StartupCheck.report("PASS: vanilla Overworld benchmark completed " + chunks + " LOD-only target chunks, with nonempty Voxy data and normal Chunky pregen; CPU load " + LodgenConfig.INSTANCE.cpuLoad());
                stage = 5; client.stop();
            }
        } catch (Throwable failure) {
            StartupCheck.report("FAIL: " + failure);
            throw new RuntimeException("Voxy benchmark failed", failure);
        }
    }
}
