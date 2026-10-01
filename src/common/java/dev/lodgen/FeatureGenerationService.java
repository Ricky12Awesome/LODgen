package dev.lodgen;

import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import dev.lodgen.minecraft.ChunkGenerationPipeline;
import dev.lodgen.minecraft.LitChunkWrapper;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** DH conversion uses its executor; native generation uses the normal chunk system. */
public final class FeatureGenerationService implements AutoCloseable {
    private final IServerLevelWrapper level;
    private final ChunkGenerationPipeline pipeline;
    private volatile boolean closed;

    public FeatureGenerationService(IServerLevelWrapper level) {
        this.level = level;
        this.pipeline = new ChunkGenerationPipeline(level.getWrappedMcObject());
        LodgenConfig.LOGGER.info("Normal chunk pipeline active for DH FEATURES in {} ({} concurrent batches)",
                level.getDimensionType(), LodgenConfig.INSTANCE.pipelineBatches());
    }

    public CompletableFuture<Void> generate(int minX, int minZ, IDhApiFullDataSource pooled,
                                             ExecutorService dhExecutor, Consumer<IDhApiFullDataSource> consumer) {
        int width = pooled.getWidthInDataColumns() / 16;
        if (((FullDataSourceV2) pooled).getDataDetailLevel() != 0 || width * 16 != pooled.getWidthInDataColumns()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected block-detail DH chunk request"));
        }
        return pipeline.generate(minX, minZ, width, dhExecutor, batch -> {
            CompletableFuture<Void> conversion;
            try {
                conversion = CompletableFuture.runAsync(() -> {
                    IWrapperFactory factory = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
                    FullDataSourceV2 destination = (FullDataSourceV2) pooled;
                    for (var nativeChunk : batch.chunks) {
                        if (closed) throw new CancellationException("Level closed");
                        var wrapped = factory.createChunkWrapper(new Object[]{nativeChunk, level.getWrappedMcObject()});
                        wrapped.createDhHeightMaps();
                        var lit = new LitChunkWrapper(wrapped, nativeChunk, level.getWrappedMcObject());
                        try (FullDataSourceV2 source = FullDataSourceV2.createFromChunk(level, lit)) {
                            if (source == null) throw new IllegalStateException("DH rejected generated chunk at " + nativeChunk.getPos());
                            destination.updateFromDataSource(source);
                        }
                    }
                    destination.recordLastSeen();
                    consumer.accept(destination);
                }, dhExecutor);
            } catch (Throwable error) {
                conversion = CompletableFuture.failedFuture(error);
            }
            return conversion;
        });
    }

    public boolean isBusy() { return pipeline.isBusy(); }

    @Override public void close() {
        closed = true;
        pipeline.close();
    }
}
