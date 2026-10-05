package dev.ricky12awesome.lodgen.integration;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import dev.ricky12awesome.lodgen.LodgenConfig;

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
    private static final java.util.concurrent.ConcurrentSkipListMap<Long, String> TERRAIN = new java.util.concurrent.ConcurrentSkipListMap<>();
    public static CompletableFuture<Void> run(DhWorldGenerator generator, ExecutorService executor) {
        int axis = Integer.getInteger("lodgen.test.benchmark", 0);
        if (axis == 0) return CompletableFuture.completedFuture(null);
        if (axis % 4 != 0) return CompletableFuture.failedFuture(new IllegalArgumentException("Benchmark axis must be divisible by 4"));
        var originalSettings = LodgenConfig.INSTANCE;
        int originalDhThreads = com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.get();
        int dhThreads = Integer.getInteger("lodgen.test.dhThreads", 0);
        if (dhThreads > 0) com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.set(dhThreads);
        boolean checkLiveConfig = Boolean.getBoolean("lodgen.test.skipWarmup")
                && Boolean.getBoolean("lodgen.test.dhQueue") && originalSettings.enabled();
        if (checkLiveConfig) {
            try {
                LodgenConfig.apply(LodgenConfig.SCHEMA.with(originalSettings, "cpuLoad", 1));
                com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.set(1);
                LodgenConfig.LOGGER.info("QUICK CHECK: applied live DH CPU load of one worker");
            } catch (Exception failure) { return CompletableFuture.failedFuture(failure); }
        }
        ExecutorService benchmarkExecutor = Boolean.getBoolean("lodgen.test.dhExecutor") ? ThreadPoolUtil.getWorldGenExecutor() : executor;
        // Give CPU JIT compilation and the optional GPU kernels a separate warmup area.
        CompletableFuture<Void> warmup = Boolean.getBoolean("lodgen.test.skipWarmup")
                ? CompletableFuture.completedFuture(null) : area(generator, benchmarkExecutor, 12000, -12000, Integer.getInteger("lodgen.test.warmupAxis", 16));
        return warmup.thenCompose(ignored -> {
            TERRAIN.clear();
            long start = System.nanoTime();
            return (Boolean.getBoolean("lodgen.test.dhQueue") ? queuedArea(generator, 10240, -10240, axis)
                    : area(generator, benchmarkExecutor, 10240, -10240, axis)).thenCompose(done -> {
                long dhNanos = System.nanoTime() - start;
                int count = axis * axis;
                double dhRate = count * 1e9 / dhNanos;
                LodgenConfig.LOGGER.info("BENCHMARK: DH {} chunks, {} chunks/s including LOD conversion", count, dhRate);
                if (!Boolean.getBoolean("lodgen.test.chunky")) return write(count, dhNanos, null);
                return ChunkyCheck.run(16384, -16384, axis).thenCompose(chunky -> write(count, dhNanos, chunky));
            });
        }).whenComplete((ignored, error) -> {
            if (dhThreads > 0 || checkLiveConfig) com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.set(originalDhThreads);
            if (checkLiveConfig) {
                try { LodgenConfig.apply(originalSettings); }
                catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
                LodgenConfig.LOGGER.info("QUICK CHECK: restored original live configuration");
            }
        });
    }

    private static CompletableFuture<Void> queuedArea(DhWorldGenerator generator, int x, int z, int axis) {
        var queue = new WorldGenerationQueue(generator, (IDhServerLevel) generator.serverLevelWrapper.getDhLevel());
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger previous = new AtomicInteger();
        var monitor = Executors.newSingleThreadScheduledExecutor();
        monitor.scheduleAtFixedRate(() -> {
            int count = completed.get();
            LodgenConfig.LOGGER.info("BENCHMARK QUEUE: {} / {} chunks; {} chunks/s; active {}; waiting {}; heap {} MiB",
                    count, axis * axis, (count - previous.getAndSet(count)) / 5.0, queue.getInProgressTaskCount(),
                    queue.getWaitingTaskCount(), ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() / 1048576);
        }, 5, 5, TimeUnit.SECONDS);
        var futures = new CompletableFuture<?>[axis * axis / 16];
        ArrayList<int[]> positions = new ArrayList<>();
        int radius = Integer.getInteger("lodgen.test.frontierRadius", 0);
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
                        fingerprint(data);
                        CompletableFuture<Void> saved = Boolean.getBoolean("lodgen.test.storeLods")
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
        if (System.getProperty("lodgen.test.layout", "row").equals("radial")) {
            Arrays.sort(order, Comparator.comparingLong(i -> {
                long dx = 2L * (i % tiles) + 1 - tiles, dz = 2L * (i / tiles) + 1 - tiles;
                return dx * dx + dz * dz;
            }));
        }
        var monitor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "lodgen-benchmark-monitor"); thread.setDaemon(true); return thread;
        });
        long start = System.nanoTime();
        AtomicInteger previous = new AtomicInteger();
        monitor.scheduleAtFixedRate(() -> {
            int count = completed.get();
            var memory = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            var lod = ThreadPoolUtil.getChunkToLodBuilderExecutor();
            LodgenConfig.LOGGER.info("BENCHMARK SAMPLE: {} / {} chunks; {} chunks/s; heap {} MiB; LOD queue {}",
                    count, axis * axis, (count - previous.getAndSet(count)) / 5.0, memory.getUsed() / 1048576,
                    lod == null ? -1 : lod.getQueueSize());
        }, 5, 5, TimeUnit.SECONDS);
        CompletableFuture<?>[] workers = new CompletableFuture[Integer.getInteger("lodgen.test.workers", 8)];
        var requests = new AdmittedRequests(() -> generator instanceof dev.ricky12awesome.lodgen.generation.GenerationAdmission admission
                && admission.lodgen$isBusy());
        for (int i = 0; i < workers.length; i++) workers[i] = next(generator, executor, requests, x, z, tiles, order, cursor, completed);
        return CompletableFuture.allOf(workers).whenComplete((ignored, error) -> {
            requests.close();
            monitor.shutdownNow();
            LodgenConfig.LOGGER.info("BENCHMARK AREA: {} chunks in {} seconds; layout {}; requests {}; pipeline {}",
                    completed.get(), (System.nanoTime() - start) / 1e9, System.getProperty("lodgen.test.layout", "row"),
                    workers.length, dev.ricky12awesome.lodgen.GenerationSettings.current().batches());
        });
    }

    private static CompletableFuture<Void> next(DhWorldGenerator generator, ExecutorService executor, AdmittedRequests requests, int x, int z, int tiles,
                                                Integer[] order, AtomicInteger cursor, AtomicInteger completed) {
        int slot = cursor.getAndIncrement();
        if (slot >= order.length) return CompletableFuture.completedFuture(null);
        int index = order[slot];
        int cx = x + index % tiles * 4, cz = z + index / tiles * 4;
        FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, cx / 4, cz / 4));
        AtomicReference<CompletableFuture<Void>> saved = new AtomicReference<>(CompletableFuture.completedFuture(null));
        return requests.submit(() -> generator.generateLod(cx, cz, cx / 4, cz / 4, (byte) 0, data,
                EDhApiDistantGeneratorMode.FEATURES, executor, output -> {
                    if (output != data) throw new AssertionError("Pooled source ownership changed");
                    if (data.getApiDataPointColumn(0, 0).isEmpty()) throw new AssertionError("Empty benchmark output");
                    fingerprint(data);
                    if (Boolean.getBoolean("lodgen.test.storeLods")) {
                        saved.set(generator.serverLevelWrapper.getDhLevel().updateDataSourcesAsync(data));
                    }
                })).thenCompose(ignored -> saved.get()).whenComplete((ignored, error) -> data.close())
                .thenComposeAsync(ignored -> {
                    completed.addAndGet(16);
                    return next(generator, executor, requests, x, z, tiles, order, cursor, completed);
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
                    System.getProperty("lodgen.test.layout", "row"), Boolean.getBoolean("lodgen.test.dhQueue")
                            ? dev.ricky12awesome.lodgen.GenerationSettings.current().batches()
                            : Integer.getInteger("lodgen.test.workers", 8),
                    dev.ricky12awesome.lodgen.GenerationSettings.current().batches(), Boolean.getBoolean("lodgen.test.dhExecutor"), Boolean.getBoolean("lodgen.test.storeLods"));
            data += ",\"dhQueue\":" + Boolean.getBoolean("lodgen.test.dhQueue");
            data += ",\"frontierRadius\":" + Integer.getInteger("lodgen.test.frontierRadius", 0);
            data += ",\"spatialBatching\":" + true;
            data += ",\"originalPredicates\":" + Boolean.getBoolean("lodgen.test.originalPredicates");
            data += ",\"originalOreAllocations\":" + Boolean.getBoolean("lodgen.test.originalOreAllocations");
            data += ",\"caveMode\":\"" + LodgenConfig.INSTANCE.caveMode().name().toLowerCase(Locale.ROOT) + "\"";
            if (Boolean.getBoolean("lodgen.test.verifyPredicates")) data += ",\"predicatesCompared\":" + DiskPredicateCheck.compared()
                    + ",\"skippedDisks\":" + DiskPredicateCheck.skippedDisks();
            if (Boolean.getBoolean("lodgen.test.verifyTerrain")) {
                if (TERRAIN.size() != count / 16) throw new AssertionError("Missing benchmark terrain fingerprints");
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                for (String tile : TERRAIN.values()) digest.update(tile.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                data += ",\"terrainSha256\":\"" + java.util.HexFormat.of().formatHex(digest.digest()) + "\"";
                data += ",\"fingerprintedTiles\":" + TERRAIN.size();
            }
            Files.writeString(Path.of("benchmark-result.json"), data + "}\n");
            LodgenConfig.LOGGER.info("BENCHMARK RESULT: {}", data + "}");
            return CompletableFuture.completedFuture(null);
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }

    /** Canonicalize DH's insertion-order palette before hashing terrain/light. */
    private static void fingerprint(FullDataSourceV2 source) {
        if (!Boolean.getBoolean("lodgen.test.verifyTerrain")) return;
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            var word = java.nio.ByteBuffer.allocate(Long.BYTES);
            String[] names = new String[source.mapping.size()];
            Integer[] order = new Integer[names.length];
            for (int id = 0; id < names.length; id++) {
                names[id] = source.mapping.getBlockStateWrapper(id).getSerialString() + "\0"
                        + source.mapping.getBiomeWrapper(id).getSerialString();
                order[id] = id;
            }
            Arrays.sort(order, Comparator.comparing(id -> names[id]));
            int[] remap = new int[names.length];
            for (int id = 0; id < names.length; id++) {
                remap[order[id]] = id;
                byte[] name = names[order[id]].getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(word.clear().putLong(name.length).array());
                digest.update(name);
            }
            digest.update(word.clear().putLong(source.getPos()).array());
            for (var column : source.dataPoints) {
                digest.update(word.clear().putLong(column == null ? -1 : column.size()).array());
                if (column == null) continue;
                for (int i = 0; i < column.size(); i++) {
                    long point = column.getLong(i);
                    long canonical = com.seibel.distanthorizons.core.util.FullDataPointUtil.setId(point,
                            remap[com.seibel.distanthorizons.core.util.FullDataPointUtil.getId(point)]);
                    digest.update(word.clear().putLong(canonical).array());
                }
            }
            TERRAIN.put(source.getPos(), java.util.HexFormat.of().formatHex(digest.digest()));
        } catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
