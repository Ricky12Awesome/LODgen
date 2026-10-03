package dev.lodgen.integration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import dev.lodgen.LodgenConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in real server test, excluded from release builds. Run only in a disposable world. */
public final class IntegrationCheck {
    private static final int LOD_X = 4096;
    private static final int LOD_Z = -4096;
    private static final Path REPORT = Path.of(Boolean.getBoolean("lodgen.test.reload") ? "integration-reload-result.txt" : "integration-result.txt");

    public static void run(MinecraftServer server) {
        if (Boolean.getBoolean("lodgen.test.tasks")) { TaskCheck.run(server); return; }
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            Files.deleteIfExists(REPORT);
            DiskPredicateCheck.run();
            int expectedWorkers = Integer.getInteger("lodgen.test.nativeWorkers", 0);
            if (expectedWorkers > 0) {
                int actualWorkers = Class.forName("com.ishland.c2me.base.common.GlobalExecutors")
                        .getField("GLOBAL_EXECUTOR_PARALLELISM").getInt(null);
                require(actualWorkers == expectedWorkers, "C2ME worker override did not apply");
                LodgenConfig.LOGGER.info("TEST SETTINGS: C2ME workers {}, Chunky working count {}", actualWorkers,
                        Integer.getInteger("chunky.maxWorkingCount", 0));
            }
            ServerLevel level = server.overworld();
            OreAllocationCheck.run(level);
            IServerLevelWrapper wrapper = null;
            for (var candidate : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
                if (candidate instanceof IServerLevelWrapper serverWrapper && candidate.getWrappedMcObject() == level) {
                    wrapper = serverWrapper;
                    break;
                }
            }
            require(wrapper != null, "DH must expose the server level");
            DhWorldGenerator generator = new DhWorldGenerator((IDhServerLevel) wrapper.getDhLevel());
            if (Boolean.getBoolean("lodgen.test.dhPlans")) { DhPlanCheck.run(server, level, generator, executor); return; }
            if (Boolean.getBoolean("lodgen.test.distance")) {
                DistanceCheck.run(server, level, generator, executor);
                return;
            }
            if (Boolean.getBoolean("lodgen.test.reload")) {
                reload(server, level, generator, executor);
                return;
            }
            var requests = new AdmittedRequests(() -> generator instanceof dev.lodgen.generation.GenerationAdmission admission
                    && admission.lodgen$isBusy());
            LodgenConfig.LOGGER.info("INTEGRATION SETTINGS: {} processors; {} native batches; direct requests honor live DH admission",
                    Runtime.getRuntime().availableProcessors(), dev.lodgen.GenerationSettings.current().batches());
            FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, LOD_X / 4, LOD_Z / 4));
            FullDataSourceV2 overlapData = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, 2048, -2048));
            FullDataSourceV2 sharedData = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, LOD_X / 4, LOD_Z / 4));
            AtomicInteger callbacks = new AtomicInteger();
            long start = System.nanoTime();
            CompletableFuture<Void> isolated = requests.submit(() -> generator.generateLod(LOD_X, LOD_Z, LOD_X / 4, LOD_Z / 4, (byte) 0,
                    data, EDhApiDistantGeneratorMode.FEATURES, executor, output -> {
                        require(output == data, "DH must retain pooled data ownership");
                        callbacks.incrementAndGet();
                    }));
            CompletableFuture<Void> overlapping = requests.submit(() -> generator.generateLod(8192, -8192, 2048, -2048, (byte) 0,
                    overlapData, EDhApiDistantGeneratorMode.FEATURES, executor, output -> {
                        require(output == overlapData, "DH must retain overlapping pooled data ownership");
                        callbacks.incrementAndGet();
                    }));
            CompletableFuture<Void> shared = requests.submit(() -> generator.generateLod(LOD_X, LOD_Z, LOD_X / 4, LOD_Z / 4, (byte) 0,
                    sharedData, EDhApiDistantGeneratorMode.FEATURES, executor, output -> {
                        require(output == sharedData, "Shared chunk request changed pooled ownership"); callbacks.incrementAndGet();
                    }));
            CompletableFuture<Void> lighting = lighting(level, executor);
            CompletableFuture<?> chunky = Boolean.getBoolean("lodgen.test.chunky")
                    ? ChunkyCheck.run(8192, -8192, 8) : CompletableFuture.completedFuture(null);
            CompletableFuture<Void> generation = CompletableFuture.allOf(isolated, overlapping, shared, lighting, chunky);

            // This uses the real server chunk pipeline while LOD workers run, like
            // Chunky's requests. It must still reach FULL and save normally.
            var fullChunk = level.getChunk(8192, -8192);
            require(fullChunk.getPersistedStatus() == ChunkStatus.FULL, "Normal generation must reach FULL");
            BlockPos sentinel = new BlockPos(8192 * 16 + 1, 80, -8192 * 16 + 1);
            level.setBlock(sentinel, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            generation.thenCompose(ignored -> BenchmarkCheck.run(generator, executor))
                    .whenComplete((ignored, error) -> server.execute(() -> {
                try {
                    if (error != null) throw new IllegalStateException("FEATURES request failed", error);
                    require(callbacks.get() == 3, "One callback per DH request");
                    if (Boolean.getBoolean("lodgen.test.verifyPredicates")) {
                        require(DiskPredicateCheck.compared() > 0, "Live disk predicate verification did not run");
                        LodgenConfig.LOGGER.info("PASS: {} live disk target evaluations exactly matched vanilla", DiskPredicateCheck.compared());
                    }
                    var adopted = level.getChunk(8193, -8191);
                    require(!dev.lodgen.minecraft.PersistenceRegistry.get(level).suppress(8193, -8191), "Cache hit must adopt ephemeral chunks");
                    BlockPos lateEdit = new BlockPos(8193 * 16 + 2, 81, -8191 * 16 + 2);
                    level.setBlock(lateEdit, Blocks.DIAMOND_BLOCK.defaultBlockState(), 3);
                    require(level.getBlockState(sentinel).is(Blocks.GOLD_BLOCK), "LOD generation changed the live overlapping chunk");
                    for (int x = 0; x < 64; x++) {
                        for (int z = 0; z < 64; z++) {
                            var column = data.getApiDataPointColumn(x, z);
                            require(column != null && !column.isEmpty(), "Missing LOD column " + x + "," + z);
                            require(!sharedData.getApiDataPointColumn(x, z).isEmpty(), "Missing shared LOD output");
                            var overlappingColumn = overlapData.getApiDataPointColumn(x, z);
                            require(overlappingColumn != null && !overlappingColumn.isEmpty(), "Missing overlapping LOD column " + x + "," + z);
                        }
                    }
                    if (LodgenConfig.INSTANCE.enabled()) require(dev.lodgen.minecraft.PersistenceRegistry.get(level).suppress(LOD_X, LOD_Z), "DH-only chunk lost save protection");
                    level.getChunkSource().save(true);
                    Path world = Path.of("world");
                    try (var paths = Files.walk(world)) {
                        require(paths.noneMatch(IntegrationCheck::isLodRegion), "LOD generation wrote region, POI, or entity data");
                    }
                    try (var paths = Files.walk(world)) {
                        require(paths.anyMatch(path -> path.getFileName().toString().equals("r.256.-256.mca")), "Normal FULL chunk was not saved");
                    }
                    long millis = (System.nanoTime() - start) / 1_000_000;
                    Files.writeString(REPORT, "PASS: FEATURES produced 12288 LOD columns; isolated area has no chunk/POI/entity files; overlapping FULL generation retained a player edit and saved normally. " + millis + " ms\n");
                    LodgenConfig.LOGGER.info("LODgen integration test passed in {} ms", millis);
                } catch (Throwable failure) {
                    fail(failure);
                } finally {
                    requests.close();
                    generator.close();
                    data.close();
                    overlapData.close();
                    sharedData.close();
                    executor.shutdown();
                    server.halt(false);
                }
            }));
        } catch (Throwable failure) {
            fail(failure);
            executor.shutdown();
            server.halt(false);
        }
    }

    private static CompletableFuture<Void> lighting(ServerLevel level, ExecutorService executor) {
        return dev.lodgen.minecraft.PersistenceRegistry.backend(level).request(LOD_X + 16, LOD_Z + 16, 4)
                .thenCompose(batch -> CompletableFuture.runAsync(() -> {
                    var factory = com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector.INSTANCE.get(
                            com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory.class);
                    for (var chunk : batch.chunks) {
                        var wrapped = factory.createChunkWrapper(new Object[]{chunk, level});
                        var snapshot = new dev.lodgen.minecraft.LitChunkWrapper(wrapped, chunk, level);
                        var engine = level.getChunkSource().getLightEngine();
                        for (int x = 1; x < 16; x += 7) for (int z = 1; z < 16; z += 7) {
                            for (int y = wrapped.getInclusiveMinBuildHeight(); y <= wrapped.getExclusiveMaxBuildHeight(); y += 13) {
                                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                                require(snapshot.getDhBlockLight(x, y, z) == engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK).getLightValue(pos), "Block lighting snapshot differs from the native engine");
                                require(snapshot.getDhSkyLight(x, y, z) == engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getLightValue(pos), "Sky lighting snapshot differs from the native engine");
                            }
                        }
                    }
                }, executor).handle((ignored, error) -> batch.release().thenApply(released -> {
                    if (error != null) throw new java.util.concurrent.CompletionException(error);
                    return (Void) null;
                })).thenCompose(java.util.function.Function.identity()));
    }

    private static void reload(MinecraftServer server, ServerLevel level, DhWorldGenerator generator, ExecutorService executor) throws Exception {
        Path savedRegion;
        try (var paths = Files.walk(Path.of("world"))) {
            savedRegion = paths.filter(path -> path.getFileName().toString().equals("r.256.-256.mca")
                    && path.getParent().getFileName().toString().equals("region")).findFirst().orElseThrow();
        }
        byte[] before = Files.readAllBytes(savedRegion);
        FullDataSourceV2 data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) 6, 2048, -2048));
        generator.generateLod(8192, -8192, 2048, -2048, (byte) 0, data,
                EDhApiDistantGeneratorMode.FEATURES, executor, output -> require(output == data, "Cold-load pooled data changed"))
                .whenComplete((ignored, error) -> server.execute(() -> {
                    try {
                        if (error != null) throw new IllegalStateException("Cold FEATURES load failed", error);
                        if (LodgenConfig.INSTANCE.enabled()) require(dev.lodgen.minecraft.PersistenceRegistry.get(level).suppress(8192, -8192), "A cold saved chunk loaded solely for DH must remain transient");
                        level.getChunkSource().save(true);
                        require(java.util.Arrays.equals(before, Files.readAllBytes(savedRegion)), "DH cold load modified an existing chunk region file");
                        require(level.getBlockState(new BlockPos(8192 * 16 + 1, 80, -8192 * 16 + 1)).is(Blocks.GOLD_BLOCK), "Concurrent normal edit did not persist");
                        require(level.getBlockState(new BlockPos(8193 * 16 + 2, 81, -8191 * 16 + 2)).is(Blocks.DIAMOND_BLOCK), "Adopted chunk edit did not persist");
                        Files.writeString(REPORT, "PASS: Normal and adopted edits survived restart; cold FEATURES loading left the existing region byte-for-byte unchanged.\n");
                    } catch (Throwable error2) { fail(error2); }
                    finally {
                        generator.close(); data.close(); executor.shutdown(); server.halt(false);
                    }
                }));
    }

    private static boolean isLodRegion(Path path) {
        String name = path.getFileName().toString();
        if (!name.matches("r\\.-?\\d+\\.-?\\d+\\.mca")) return false;
        String[] parts = name.split("\\.");
        int x = Integer.parseInt(parts[1]);
        int z = Integer.parseInt(parts[2]);
        return Math.abs(x - 128) <= 2 && Math.abs(z + 128) <= 2;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void fail(Throwable failure) {
        LodgenConfig.LOGGER.error("LODgen integration test failed", failure);
        try { Files.writeString(REPORT, "FAIL: " + failure + "\n"); }
        catch (Exception error) { failure.addSuppressed(error); }
    }
}
