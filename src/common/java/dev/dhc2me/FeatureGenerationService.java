package dev.dhc2me;

import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import dev.dhc2me.generation.BatchGate;
import dev.dhc2me.minecraft.NormalChunkBackend;
import dev.dhc2me.minecraft.LitChunkWrapper;
import dev.dhc2me.minecraft.PersistenceRegistry;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** DH conversion uses its executor; native generation uses the normal chunk system. */
public final class FeatureGenerationService implements AutoCloseable {
    private final IServerLevelWrapper level;
    private final NormalChunkBackend backend;
    private final BatchGate gate;
    private volatile boolean closed;

    public FeatureGenerationService(IServerLevelWrapper level) {
        this.level = level;
        this.backend = PersistenceRegistry.backend(level.getWrappedMcObject());
        this.gate = new BatchGate(DhC2meConfig.INSTANCE.concurrentBatches(), DhC2meConfig.INSTANCE.queuedBatches());
        DhC2meConfig.LOGGER.info("Normal chunk pipeline active for DH FEATURES in {} ({} concurrent batches)",
                level.getDimensionType(), DhC2meConfig.INSTANCE.concurrentBatches());
    }

    public CompletableFuture<Void> generate(int minX, int minZ, IDhApiFullDataSource pooled,
                                             ExecutorService dhExecutor, Consumer<IDhApiFullDataSource> consumer) {
        int width = pooled.getWidthInDataColumns() / 16;
        if (((FullDataSourceV2) pooled).getDataDetailLevel() != 0 || width * 16 != pooled.getWidthInDataColumns()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expected block-detail DH chunk request"));
        }
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

    @Override public void close() { closed = true; gate.close(); }
}
