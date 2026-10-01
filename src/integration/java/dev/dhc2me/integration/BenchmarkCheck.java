package dev.dhc2me.integration;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import dev.dhc2me.DhC2meConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Arrays;
import java.util.Comparator;
import java.util.ArrayList;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;

public final class BenchmarkCheck {
    public static CompletableFuture<Void> run(DhWorldGenerator generator, ExecutorService executor) {
        int axis = Integer.getInteger("dhc2me.test.benchmark", 0);
        if (axis == 0) return CompletableFuture.completedFuture(null);
        if (axis % 4 != 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Benchmark axis must be divisible by 4"));
        ExecutorService benchmarkExecutor = Boolean.getBoolean("dhc2me.test.dhExecutor") ? ThreadPoolUtil.getWorldGenExecutor() : executor;
        // Give CPU JIT compilation and the optional GPU kernels a separate warmup area.
        return area(generator, benchmarkExecutor, 12000, -12000, 16).thenCompose(ignored -> {
            long start = System.nanoTime();
            return (Boolean.getBoolean("dhc2me.test.dhQueue") ? queuedArea(generator, 10240, -10240, axis)
                    : area(generator, benchmarkExecutor, 10240, -10240, axis)).thenCompose(done -> {
                long dhNanos = System.nanoTime() - start;
                int count = axis * axis;
                double dhRate = count * 1e9 / dhNanos;
                DhC2meConfig.LOGGER.info("BENCHMARK: DH {} chunks, {} chunks/s including LOD conversion", count, dhRate);
                if (!Boolean.getBoolean("dhc2me.test.chunky")) return write(count, dhNanos, null);
                return ChunkyCheck.run(16384, -16384, axis).thenCompose(chunky -> write(count, dhNanos, chunky));
            });
        });
    }

    private static CompletableFuture<Void> queuedArea(DhWorldGenerator generator, int x, int z, int axis) {
        var queue = new WorldGenerationQueue(generator, (IDhServerLevel) generator.serverLevelWrapper.getDhLevel());
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger previous = new AtomicInteger();
        var monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            int count = completed.get();
            DhC2meConfig.LOGGER.info("BENCHMARK QUEUE: {} / {} chunks; {} chunks/s; active {}; waiting {}; heap {} MiB",
                    count, axis * axis, (count - previous.getAndSet(count)) / 5.0, queue.getInProgressTaskCount(),
                    queue.getWaitingTaskCount(), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576);
        }, 5, 5, TimeUnit.SECONDS);
        var futures = new CompletableFuture<?>[axis * axis / 16];
        ArrayList<int[]> positions = new ArrayList<>();
        int radius = Integer.getInteger("dhc2me.test.frontierRadius", 0);
        if (radius > 0) {
            int bound = (radius + axis) / 4;
            for (int dx = -bound; dx <= bound; dx++) for (int dz = -bound; dz <= bound; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) * 4 >= radius) positions.add(new int[]{dx, dz});
            }
            positions.sort(Comparator.comparingInt(p -> Math.max(Math.abs(p[0]), Math.abs(p[1]))));
        }
        for (int i = 0; i < futures.length; i++) {
            int cx = radius == 0 ? x + i % (axis / 4) * 4 : x + axis / 2 + positions.get(i)[0] * 4;
            int cz = radius == 0 ? z + i / (axis / 4) * 4 : z + axis / 2 + positions.get(i)[1] * 4;
            futures[i] = queue.submitRetrievalTask(DhSectionPos.encode((byte) 6, cx / 4, cz / 4), (byte) 0)
                    .thenCompose(result -> {
                        FullDataSourceV2 data = result.dataSource;
                        if (data == null || data.getApiDataPointColumn(0, 0).isEmpty()) {
                            return CompletableFuture.failedFuture(new AssertionError("Empty queue result"));
                        }
                        CompletableFuture<Void> saved = Boolean.getBoolean("dhc2me.test.storeLods")
                                ? generator.serverLevelWrapper.getDhLevel().updateDataSourcesAsync(data)
                                : CompletableFuture.completedFuture(null);
                        return saved.whenComplete((ignored, error) -> data.close()).thenRun(() -> completed.addAndGet(16));
                    });
        }
        queue.startAndSetTargetPos(new DhBlockPos2D((x + axis / 2) * 16, (z + axis / 2) * 16));
        return CompletableFuture.allOf(futures).whenComplete((ignored, error) -> {
            monitor.shutdownNow();
            queue.close();
        });
    }

    private static CompletableFuture<Void> area(DhWorldGenerator generator, ExecutorService executor, int x, int z, int axis) {
        AtomicInteger cursor = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        int tiles = axis / 4;
        Integer[] order = new Integer[tiles * tiles];
        Arrays.setAll(order, i -> i);
        if (System.getProperty("dhc2me.test.layout", "row").equals("radial")) {
            Arrays.sort(order, Comparator.comparingLong(i -> {
                long dx = 2L * (i % tiles) + 1 - tiles, dz = 2L * (i / tiles) + 1 - tiles;
                return dx * dx + dz * dz;
            }));
        }
        var monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "dhc2me-benchmark-monitor"); thread.setDaemon(true); return thread;
        });
        long start = System.nanoTime();
        AtomicInteger previous = new AtomicInteger();
        monitor.scheduleAtFixedRate(() -> {
            int count = completed.get();
            var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            var lod = ThreadPoolUtil.getChunkToLodBuilderExecutor();
            DhC2meConfig.LOGGER.info("BENCHMARK SAMPLE: {} / {} chunks; {} chunks/s; heap {} MiB; LOD queue {}",
                    count, axis * axis, (count - previous.getAndSet(count)) / 5.0, memory.getUsed() / 1048576,
                    lod == null ? -1 : lod.getQueueSize());
        }, 5, 5, TimeUnit.SECONDS);
        CompletableFuture<?>[] workers = new CompletableFuture[Integer.getInteger("dhc2me.test.workers", 8)];
        for (int i = 0; i < workers.length; i++) workers[i] = next(generator, executor, x, z, tiles, order, cursor, completed);
        return CompletableFuture.allOf(workers).whenComplete((ignored, error) -> {
            monitor.shutdownNow();
            DhC2meConfig.LOGGER.info("BENCHMARK AREA: {} chunks in {} seconds; layout {}; requests {}; pipeline {}",
                    completed.get(), (System.nanoTime() - start) / 1e9, System.getProperty("dhc2me.test.layout", "row"),
                    workers.length, DhC2meConfig.INSTANCE.concurrentBatches());
        });
    }

    private static CompletableFuture<Void> next(DhWorldGenerator generator, ExecutorService executor, int x, int z, int tiles,
                                                Integer[] order, AtomicInteger cursor, AtomicInteger completed) {
        int slot = cursor.getAndIncrement();
        if (slot >= order.length) return CompletableFuture.completedFuture(null);
        int index = order[slot];
        int cx = x + index % tiles * 4, cz = z + index / tiles * 4;
        FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, cx / 4, cz / 4));
        AtomicReference<CompletableFuture<Void>> saved = new AtomicReference<>(CompletableFuture.completedFuture(null));
        return generator.generateLod(cx, cz, cx / 4, cz / 4, (byte) 0, data,
                EDhApiDistantGeneratorMode.FEATURES, executor, output -> {
                    if (output != data) throw new AssertionError("Pooled source ownership changed");
                    if (data.getApiDataPointColumn(0, 0).isEmpty()) throw new AssertionError("Empty benchmark output");
                    if (Boolean.getBoolean("dhc2me.test.storeLods")) {
                        saved.set(generator.serverLevelWrapper.getDhLevel().updateDataSourcesAsync(data));
                    }
                }).thenCompose(ignored -> saved.get()).whenComplete((ignored, error) -> data.close())
                .thenComposeAsync(ignored -> {
                    completed.addAndGet(16);
                    return next(generator, executor, x, z, tiles, order, cursor, completed);
                }, executor);
    }

    private static CompletableFuture<Void> write(int count, long nanos, ChunkyCheck.Result chunky) {
        try {
            String data = String.format(Locale.ROOT, "{\"dhChunks\":%d,\"dhSeconds\":%.6f,\"dhChunksPerSecond\":%.3f",
                    count, nanos / 1e9, count * 1e9 / nanos);
            if (chunky != null) {
                data += String.format(Locale.ROOT, ",\"chunkyChunks\":%d,\"chunkySeconds\":%.6f,\"chunkyChunksPerSecond\":%.3f",
                        chunky.chunks(), chunky.nanos() / 1e9, chunky.chunks() * 1e9 / chunky.nanos());
            }
            data += String.format(Locale.ROOT, ",\"layout\":\"%s\",\"requests\":%d,\"pipelineBatches\":%d,\"dhExecutor\":%s,\"storeLods\":%s",
                    System.getProperty("dhc2me.test.layout", "row"), Boolean.getBoolean("dhc2me.test.dhQueue")
                            ? com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.get() + 1
                            : Integer.getInteger("dhc2me.test.workers", 8),
                    DhC2meConfig.INSTANCE.concurrentBatches(), Boolean.getBoolean("dhc2me.test.dhExecutor"), Boolean.getBoolean("dhc2me.test.storeLods"));
            data += ",\"dhQueue\":" + Boolean.getBoolean("dhc2me.test.dhQueue");
            data += ",\"frontierRadius\":" + Integer.getInteger("dhc2me.test.frontierRadius", 0);
            data += ",\"spatialBatching\":" + DhC2meConfig.INSTANCE.spatialBatching();
            Files.writeString(Path.of("benchmark-result.json"), data + "}\n");
            DhC2meConfig.LOGGER.info("BENCHMARK RESULT: {}", data + "}");
            return CompletableFuture.completedFuture(null);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }
}
