package dev.lodgen.minecraft;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Optional DH adapter for generation that does not originate in DH's request queue. */
final class DhTaskSink {
    static IServerLevelWrapper wrapper(ServerLevel level) {
        if (DhApi.Delayed.worldProxy == null) return null;
        for (var candidate : DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
            if (candidate instanceof IServerLevelWrapper server && server.getWrappedMcObject() == level && server.getDhLevel() != null) return server;
        }
        return null;
    }
    static boolean ready(ServerLevel level) { return wrapper(level) != null; }
    static boolean featuresActive() {
        return com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.chunkGeneratorMode.get()
                == com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES
                && com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled;
    }
    static int radius() { return com.seibel.distanthorizons.core.config.Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get(); }
    static dev.lodgen.generation.GenerationProgress progress(ServerLevel level, int radius) {
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
                var state = remaining == 0 ? dev.lodgen.generation.GenerationProgress.State.COMPLETE
                        : dev.lodgen.generation.GenerationProgress.State.RUNNING;
                return new dev.lodgen.generation.GenerationProgress(radius, remaining, state);
            }
        }
        return new dev.lodgen.generation.GenerationProgress(radius, -1, dev.lodgen.generation.GenerationProgress.State.WAITING_FOR_RENDERER);
    }
    static CompletableFuture<Void> convert(ServerLevel level, List<ChunkAccess> chunks, Executor executor) {
        var wrapper = wrapper(level);
        if (wrapper == null) return CompletableFuture.failedFuture(new IllegalStateException("Waiting for DH to open " + level.dimension()));
        return CompletableFuture.supplyAsync(() -> {
            var pending = new ArrayList<CompletableFuture<Void>>();
            var factory = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
            var destinations = new java.util.HashMap<Long, FullDataSourceV2>();
            try {
                for (var chunk : chunks) {
                    var wrapped = factory.createChunkWrapper(new Object[]{chunk, level});
                    wrapped.createDhHeightMaps();
                    try (var source = FullDataSourceV2.createFromChunk(wrapper, new LitChunkWrapper(wrapped, chunk, level))) {
                        if (source == null) throw new IllegalStateException("DH rejected " + chunk.getPos());
                        destinations.computeIfAbsent(source.getPos(), FullDataSourceV2::createEmpty).updateFromDataSource(source);
                    }
                }
                for (var data : destinations.values()) {
                    data.recordLastSeen();
                    pending.add(wrapper.getDhLevel().updateDataSourcesAsync(data));
                }
            } catch (Throwable error) {
                CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> destinations.values().forEach(FullDataSourceV2::close));
                throw error;
            }
            pending.add(CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> destinations.values().forEach(FullDataSourceV2::close)));
            return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new));
        }, executor).thenCompose(java.util.function.Function.identity());
    }
}
