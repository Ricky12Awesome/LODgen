package dev.lodgen.integration;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import dev.lodgen.LodgenConfig;
import dev.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Two 4x4 sections, not a 64/128-radius pregen. Tests the actual DH dispatch queue. */
public final class DistanceCheck {
    public static void run(MinecraftServer server, ServerLevel level, DhWorldGenerator generator, ExecutorService executor) throws Exception {
        var original = LodgenConfig.INSTANCE;
        var originalMode = Config.Common.WorldGenerator.chunkGeneratorMode.get();
        int originalDhDistance = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
        Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.set(128);
        Config.Common.WorldGenerator.chunkGeneratorMode.set(EDhApiDistantGeneratorMode.FEATURES);
        if (!Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) throw new AssertionError("Test needs chunk-enabled generator plan");
        LodgenConfig.apply(new LodgenConfig(true, 1, 0, true, 64));
        var queue = new WorldGenerationQueue(generator, (IDhServerLevel) generator.serverLevelWrapper.getDhLevel());
        int centerX = 4096, centerZ = -4096;
        int insideX = centerX + 60, outsideX = centerX + 96;
        var inside = queue.submitRetrievalTask(DhSectionPos.encode((byte) 6, insideX / 4, centerZ / 4), (byte) 0);
        var outside = queue.submitRetrievalTask(DhSectionPos.encode((byte) 6, outsideX / 4, centerZ / 4), (byte) 0);
        queue.startAndSetTargetPos(new DhBlockPos2D(centerX * 16, centerZ * 16));
        CompletableFuture.allOf(inside, outside.handle((data, error) -> null)).orTimeout(30, TimeUnit.SECONDS)
                .thenCompose(ignored -> {
                    if (!outside.isCancelled()) throw new AssertionError("Outside custom 64 request was not cancelled");
                    var data = inside.join().dataSource;
                    if (data == null || data.getApiDataPointColumn(0, 0).isEmpty()) throw new AssertionError("Inside custom radius did not generate LODs");
                    data.close();
                    if (PersistenceRegistry.get(level).suppress(outsideX, centerZ)) throw new AssertionError("Outside custom radius reached native generation");
                    try { LodgenConfig.apply(new LodgenConfig(true, 1, 0, true, 0)); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    return queue.submitRetrievalTask(DhSectionPos.encode((byte) 6, outsideX / 4, centerZ / 4), (byte) 0)
                            .orTimeout(30, TimeUnit.SECONDS);
                }).whenComplete((result, failure) -> server.execute(() -> {
                    try {
                        if (failure != null) throw new RuntimeException(failure);
                        var data = result.dataSource;
                        if (data == null || data.getApiDataPointColumn(0, 0).isEmpty()) throw new AssertionError("Zero did not restore normal request range");
                        data.close();
                        if (!PersistenceRegistry.get(level).suppress(outsideX, centerZ)) throw new AssertionError("Restored request did not use transient native chunks");
                        if (Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get() != 128) throw new AssertionError("Changed DH render distance");
                        Files.writeString(Path.of("integration-result.txt"), "PASS: custom 64 with DH 128 generated the section at 60 chunks and cancelled the section at 96 before native generation; live reset to 0 allowed that section; DH stayed 128. Generated only 32 target chunks.\n");
                    } catch (Throwable error) {
                        LodgenConfig.LOGGER.error("Distance check failed", error);
                        try { Files.writeString(Path.of("integration-result.txt"), "FAIL: " + error + "\n"); }
                        catch (Exception writeError) { error.addSuppressed(writeError); }
                    } finally {
                        queue.close(); generator.close(); executor.shutdown();
                        try { LodgenConfig.apply(original); }
                        catch (Exception error) { LodgenConfig.LOGGER.error("Cannot restore fixture settings", error); }
                        Config.Common.WorldGenerator.chunkGeneratorMode.set(originalMode);
                        Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.set(originalDhDistance);
                        server.halt(false);
                    }
                }));
    }
}
