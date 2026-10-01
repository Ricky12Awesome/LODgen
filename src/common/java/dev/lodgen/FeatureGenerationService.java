package dev.lodgen;

import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import dev.lodgen.generation.BatchGate;
import dev.lodgen.minecraft.NormalChunkBackend;
import dev.lodgen.minecraft.LitChunkWrapper;
import dev.lodgen.minecraft.PersistenceRegistry;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** DH conversion uses its executor; native generation uses the normal chunk system. */
public final class FeatureGenerationService implements AutoCloseable {
    private final IServerLevelWrapper level;
    private final NormalChunkBackend backend;
    private final BatchGate gate;
    private final Consumer<LodgenConfig> configListener;
    private volatile boolean closed;

    public FeatureGenerationService(IServerLevelWrapper level) {
        this.level = level;
        this.backend = PersistenceRegistry.backend(level.getWrappedMcObject());
        this.gate = new BatchGate(LodgenConfig.INSTANCE.pipelineBatches(), LodgenConfig.INSTANCE.queuedBatches());
        this.configListener = settings -> gate.reconfigure(settings.pipelineBatches(), settings.queuedBatches());
        LodgenConfig.listen(configListener);
        LodgenConfig.LOGGER.info("Normal chunk pipeline active for DH FEATURES in {} ({} concurrent batches)",
                level.getDimensionType(), LodgenConfig.INSTANCE.pipelineBatches());
    }

    public CompletableFuture<Void> generate(int minX, int minZ, IDhApiFullDataSource pooled,
                                             ExecutorService dhExecutor, Consumer<IDhApiFullDataSource> consumer) {
        int width = pooled.getWidthInDataColumns() / 16;
        if (((FullDataSourceV2) pooled).getDataDetailLevel() != 0 || width * 16 != pooled.getWidthInDataColumns()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected block-detail DH chunk request"));
        }
        var settings = LodgenConfig.INSTANCE;
        gate.reconfigure(settings.pipelineBatches(), settings.queuedBatches());
        return gate.submit(dhExecutor, () -> backend.request(minX, minZ, width).thenCompose(batch -> {
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
            // Never release tickets before the pooled LOD source has finished reading
            // chunks and lighting. Drain cleanup before completing DH's future.
            return conversion.handle((ignored, error) -> batch.release().thenApply(released -> {
                if (error != null) throw new java.util.concurrent.CompletionException(error);
                return (Void) null;
            })).thenCompose(java.util.function.Function.identity());
        }));
    }

    public boolean isBusy() { return gate.isBusy(); }

    @Override public void close() {
        closed = true;
        LodgenConfig.stopListening(configListener);
        gate.close();
    }
}
