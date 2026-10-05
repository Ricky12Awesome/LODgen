package dev.ricky12awesome.lodgen.minecraft;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import dev.ricky12awesome.lodgen.util.Futures;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Optional DH adapter for generation that does not originate in DH's request queue. */
final class DhTaskSink {
    static IServerLevelWrapper wrapper(ServerLevel level) {
        var proxy = DhApi.Delayed.worldProxy;
        if (proxy == null || !proxy.worldLoaded()) return null;
        try {
            for (var candidate : proxy.getAllLoadedLevelWrappers()) {
                if (candidate instanceof IServerLevelWrapper server && server.getWrappedMcObject() == level && server.getDhLevel() != null) return server;
            }
        } catch (IllegalStateException error) {
            // DH can close on the render thread between these two API calls.
            if (proxy.worldLoaded()) throw error;
        }
        return null;
    }
    static boolean ready(ServerLevel level) { return wrapper(level) != null; }
    static int radius() { return com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get(); }
    /** Fixed/opt-in areas aren't requested by the viewport. Run their rough
     * phase through DH's unchanged generator before submitting native chunks.
     * Coarse sections cover the area with a bounded number of active sources.
     */
    static CompletableFuture<Void> surface(ServerLevel level, dev.ricky12awesome.lodgen.generation.GenerationArea area,
                                           java.util.concurrent.ExecutorService executor, java.util.function.BooleanSupplier allowed) {
        var wrapper = wrapper(level);
        if (wrapper == null) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("DH is not ready"));
        var generator = new com.seibel.distanthorizons.core.generation.DhWorldGenerator(
                (com.seibel.distanthorizons.core.level.IDhServerLevel) wrapper.getDhLevel());
        int detail = 1;
        while (detail < 12 && (4L << detail) < 2L * area.radius()) detail++;
        var pass = new SurfacePass(generator, area, (byte) detail, executor, allowed);
        return pass.next().whenComplete((ignored, failure) -> generator.close());
    }
    private static final class SurfacePass {
        final com.seibel.distanthorizons.core.generation.DhWorldGenerator generator;
        final java.util.concurrent.ExecutorService executor;
        final java.util.function.BooleanSupplier allowed;
        final byte detail;
        final int width, minX, maxX, maxZ;
        int x, z;
        SurfacePass(com.seibel.distanthorizons.core.generation.DhWorldGenerator generator,
                    dev.ricky12awesome.lodgen.generation.GenerationArea area, byte detail,
                    java.util.concurrent.ExecutorService executor, java.util.function.BooleanSupplier allowed) {
            this.generator = generator; this.detail = detail; this.executor = executor; this.allowed = allowed;
            width = 4 << detail;
            int edge = dev.ricky12awesome.lodgen.generation.GenerationArea.WORLD_EDGE_BLOCKS / 16;
            minX = x = Math.floorDiv(Math.max(-edge, area.chunkX() - area.radius()), width);
            z = Math.floorDiv(Math.max(-edge, area.chunkZ() - area.radius()), width);
            maxX = Math.floorDiv(Math.min(edge, area.chunkX() + area.radius()) - 1, width);
            maxZ = Math.floorDiv(Math.min(edge, area.chunkZ() + area.radius()) - 1, width);
        }
        CompletableFuture<Void> next() {
            if (!allowed.getAsBoolean()) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Automatic surface pass stopped"));
            if (z > maxZ) return CompletableFuture.completedFuture(null);
            int sx = x, sz = z;
            if (++x > maxX) { x = minX; z++; }
            var data = FullDataSourceV2.createEmpty(com.seibel.distanthorizons.core.pos.DhSectionPos.encode((byte) (6 + detail), sx, sz));
            try {
                return generator.generateLod(sx * width, sz * width, sx, sz, detail, data,
                        com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES, executor, ignored -> {})
                        .thenCompose(ignored -> {
                            data.recordLastSeen();
                            return generator.serverLevelWrapper.getDhLevel().updateDataSourcesAsync(data);
                        }).whenComplete((ignored, failure) -> data.close())
                        .thenComposeAsync(ignored -> next(), executor);
            } catch (Throwable error) { data.close(); return CompletableFuture.failedFuture(error); }
        }
    }
    static dev.ricky12awesome.lodgen.generation.GenerationProgress progress(ServerLevel level, int radius) {
        var wrapper = wrapper(level);
        if (wrapper != null && wrapper.getDhLevel().getFullDataProvider()
                instanceof com.seibel.distanthorizons.core.file.fullDatafile.GeneratedFullDataSourceProvider provider) {
            var queue = provider.worldGenQueueRef.get();
            if (queue != null) {
                long remaining = Math.max(0, queue.getRetrievalEstimatedRemainingChunkCount());
                // The queued-chunk getter scans the whole waiting map. Read
                // DH's maintained estimate and constant-time counts instead.
                if (remaining == 0 && (queue.getWaitingTaskCount() > 0 || queue.getInProgressTaskCount() > 0)) remaining = -1;
                if (radius > 0) remaining = Math.min(remaining, 4L * radius * radius);
                var state = remaining == 0 ? dev.ricky12awesome.lodgen.generation.GenerationProgress.State.COMPLETE
                        : dev.ricky12awesome.lodgen.generation.GenerationProgress.State.RUNNING;
                return new dev.ricky12awesome.lodgen.generation.GenerationProgress(radius, remaining, state);
            }
        }
        return new dev.ricky12awesome.lodgen.generation.GenerationProgress(radius, -1, dev.ricky12awesome.lodgen.generation.GenerationProgress.State.WAITING_FOR_RENDERER);
    }
    static CompletableFuture<Void> convert(ServerLevel level, ServerLevel sourceLevel, List<ChunkAccess> chunks, Executor executor) {
        var wrapper = wrapper(level);
        var dhLevel = wrapper == null ? null : wrapper.getDhLevel();
        if (dhLevel == null) return CompletableFuture.failedFuture(new CancellationException("DH level is unavailable"));
        return Futures.attempt(() -> CompletableFuture.supplyAsync(() -> convertChunks(level, sourceLevel, chunks, wrapper, dhLevel), executor))
                .thenCompose(java.util.function.Function.identity())
                .handle((ignored, error) -> {
                    if (error != null) {
                        if (!current(level, wrapper, dhLevel)) throw new CancellationException("DH level closed during conversion");
                        throw new CompletionException(error);
                    }
                    return (Void) null;
                });
    }

    private static boolean current(ServerLevel level, IServerLevelWrapper wrapper, IDhLevel dhLevel) {
        return wrapper(level) == wrapper && wrapper.getDhLevel() == dhLevel;
    }

    private static void requireCurrent(ServerLevel level, IServerLevelWrapper wrapper, IDhLevel dhLevel) {
        if (!current(level, wrapper, dhLevel)) throw new CancellationException("DH level closed during conversion");
    }

    private static CompletableFuture<Void> convertChunks(ServerLevel level, ServerLevel sourceLevel,
            List<ChunkAccess> chunks, IServerLevelWrapper wrapper, IDhLevel dhLevel) {
        // DH can unload while this conversion waits for a worker. Keep one
        // destination for the batch and reject work belonging to a closed world.
        requireCurrent(level, wrapper, dhLevel);
        var converter = new DhChunkConversion(wrapper, level);
        var destinations = new java.util.HashMap<Long, FullDataSourceV2>();
        var writes = new ArrayList<CompletableFuture<Void>>();
        try {
            for (var chunk : chunks) {
                try (var source = converter.create(chunk, sourceLevel)) {
                    destinations.computeIfAbsent(source.getPos(), FullDataSourceV2::createEmpty).updateFromDataSource(source);
                }
            }
            for (var data : destinations.values()) {
                requireCurrent(level, wrapper, dhLevel);
                data.recordLastSeen();
                writes.add(dhLevel.updateDataSourcesAsync(data));
            }
        } catch (Throwable error) {
            return drainWritesAndClose(destinations, writes).handle((ignored, writeError) -> {
                throw new java.util.concurrent.CompletionException(error);
            });
        }
        return drainWritesAndClose(destinations, writes);
    }

    private static CompletableFuture<Void> drainWritesAndClose(
            java.util.Map<Long, FullDataSourceV2> destinations, List<CompletableFuture<Void>> writes) {
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, error) -> destinations.values().forEach(FullDataSourceV2::close));
    }
}
