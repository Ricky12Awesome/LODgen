package dev.lodgen.minecraft;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.BatchGate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared admission and ticket lifetime for both renderer integrations. */
public final class ChunkGenerationPipeline implements AutoCloseable {
    private final NormalChunkBackend backend;
    private final net.minecraft.server.level.ServerLevel level;
    private final dev.lodgen.generation.ChunkThroughput throughput;
    private final BatchGate gate;
    private final Consumer<LodgenConfig> listener;

    public ChunkGenerationPipeline(Object level) {
        this.level = (net.minecraft.server.level.ServerLevel) level;
        backend = PersistenceRegistry.backend(level);
        throughput = PersistenceRegistry.throughput(level);
        gate = new BatchGate(dev.lodgen.GenerationSettings.current().batches(), 0);
        listener = settings -> refresh();
        LodgenConfig.listen(listener);
    }

    public CompletableFuture<Void> generate(int x, int z, int width, Executor executor,
                                            Function<NormalChunkBackend.Batch, CompletableFuture<Void>> convert) {
        return generate(x, z, width, width, GenerationCenters.automatic(level, LodgenConfig.INSTANCE.generationDistance()), executor, convert);
    }

    public CompletableFuture<Void> generate(int x, int z, int width, int height, dev.lodgen.generation.GenerationArea area, Executor executor,
                                            Function<NormalChunkBackend.Batch, CompletableFuture<Void>> convert) {
        refresh();
        return gate.submit(executor, () -> backend.request(x, z, width, height, area).thenCompose(batch -> {
            CompletableFuture<Void> conversion;
            try { conversion = convert.apply(batch); }
            catch (Throwable error) { conversion = CompletableFuture.failedFuture(error); }
            // Cleanup drains before the public future can return pooled data or
            // let a renderer advance its completed frontier.
            return conversion.handle((ignored, error) -> batch.release().thenApply(released -> {
                if (error != null) throw new java.util.concurrent.CompletionException(error);
                throughput.completed(batch.chunks.size());
                return (Void) null;
            })).thenCompose(Function.identity());
        }));
    }

    private void refresh() { gate.reconfigure(dev.lodgen.GenerationSettings.current().batches(), 0); }
    public boolean isBusy() { refresh(); return gate.isBusy(); }
    @Override public void close() { LodgenConfig.stopListening(listener); gate.close(); }
}
