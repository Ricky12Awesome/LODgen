package dev.lodgen.startup;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationCenter;
import dev.lodgen.minecraft.PersistenceRegistry;
import dev.lodgen.voxy.VoxyBridge;
import net.minecraft.client.Minecraft;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Time the actual automatic task, then quit with native work still active. */
public final class VoxyAutomaticCheck {
    private static final ArrayList<Map<String, Object>> samples = new ArrayList<>();
    private static long started, sampleTime, previous, baseline;
    private static int nativeWorkers;
    private static boolean finished;

    public static void tick(Minecraft client) {
        if (finished || client.level == null || client.player == null || client.getSingleplayerServer() == null) return;
        try {
            var level = client.getSingleplayerServer().overworld();
            if (new VoxyBridge().context(level) == null) return;
            var throughput = PersistenceRegistry.throughput(level);
            if (started == 0) {
                nativeWorkers = Class.forName("com.ishland.c2me.base.common.GlobalExecutors").getField("GLOBAL_EXECUTOR_PARALLELISM").getInt(null);
                LodgenConfig.apply(new LodgenConfig(true, Integer.getInteger("lodgen.test.cpuLoad", 5),
                        Integer.getInteger("lodgen.test.automaticRadius", 256), true, 250, GenerationCenter.CUSTOM,
                        Integer.getInteger("lodgen.test.automaticCenterX", 163840), Integer.getInteger("lodgen.test.automaticCenterZ", -163840), 0));
                baseline = throughput.totalCompleted(); started = sampleTime = System.nanoTime(); return;
            }
            long now = System.nanoTime(), completed = throughput.totalCompleted() - baseline;
            double elapsed = (now - started) / 1e9;
            int duration = Integer.getInteger("lodgen.test.automaticSeconds", 75);
            if (now - sampleTime >= 5_000_000_000L || elapsed >= duration) {
                var row = new LinkedHashMap<String, Object>();
                row.put("seconds", elapsed); row.put("completed", completed);
                row.put("chunksPerSecond", (completed - previous) * 1e9 / (now - sampleTime));
                row.put("overlayChunksPerSecond", throughput.chunksPerSecond());
                row.put("nativeLoadedChunks", level.getChunkSource().getLoadedChunksCount());
                row.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                var current = dev.lodgen.minecraft.GenerationTasks.class.getDeclaredField("command"); current.setAccessible(true);
                Object job = current.get(dev.lodgen.minecraft.GenerationTasks.get(level.getServer()));
                if (job != null) {
                    var active = job.getClass().getDeclaredField("active"); active.setAccessible(true);
                    row.put("activeTiles", ((Map<?, ?>) active.get(job)).size());
                    var progress = dev.lodgen.minecraft.GenerationTasks.displaySource(level).progress();
                    row.put("remainingChunks", progress.remainingChunks());
                    row.put("status", progress.state().name());
                }
                samples.add(row); previous = completed; sampleTime = now;
                LodgenConfig.LOGGER.info("VOXY AUTOMATIC SAMPLE: {}", row);
            }
            if (elapsed < duration) return;
            if (duration >= 10 && completed == 0) throw new AssertionError("Automatic generation made no progress");
            var last = samples.get(samples.size() - 1);
            if (!last.containsKey("activeTiles") || ((Number) last.get("activeTiles")).intValue() == 0)
                throw new AssertionError("Shutdown probe needs unfinished native work");
            int x = LodgenConfig.INSTANCE.centerX(), z = LodgenConfig.INSTANCE.centerZ();
            if (duration >= 10 && (Math.abs(x) > 4096 || Math.abs(z) > 4096) && !PersistenceRegistry.get(level).suppress(x / 16, z / 16))
                throw new AssertionError("LOD-only targets were adopted");
            var result = new LinkedHashMap<String, Object>();
            result.put("automatic", true); result.put("seconds", elapsed); result.put("completed", completed);
            result.put("chunksPerSecond", completed / elapsed); result.put("nativeWorkers", nativeWorkers);
            result.put("cpuLoad", LodgenConfig.INSTANCE.cpuLoad()); result.put("radiusChunks", LodgenConfig.INSTANCE.generationDistance());
            result.put("heapBytes", Runtime.getRuntime().maxMemory()); result.put("samples", samples);
            result.put("centerX", x); result.put("centerZ", z);
            result.put("quitWithActiveTiles", last.get("activeTiles"));
            Files.writeString(Path.of("automatic-result.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
            StartupCheck.report("PASS: automatic Voxy generated " + completed + " targets in " + elapsed + " seconds; quit with " + last.get("activeTiles") + " active tiles");
            finished = true; client.stop();
        } catch (Throwable failure) {
            StartupCheck.report("FAIL: " + failure);
            throw new RuntimeException("Automatic Voxy check failed", failure);
        }
    }
}
