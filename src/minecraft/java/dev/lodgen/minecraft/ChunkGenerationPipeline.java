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
    private final BatchGate gate;
    private final Consumer<LodgenConfig> listener;

    public ChunkGenerationPipeline(Object level) {
        backend = PersistenceRegistry.backend(level);
        gate = new BatchGate(LodgenConfig.INSTANCE.pipelineBatches(), LodgenConfig.INSTANCE.queuedBatches());
        listener = settings -> gate.reconfigure(settings.pipelineBatches(), settings.queuedBatches());
        LodgenConfig.listen(listener);
    }

    public CompletableFuture<Void> generate(int x, int z, int width, Executor executor,
                                            Function<NormalChunkBackend.Batch, CompletableFuture<Void>> convert) {
        return gate.submit(executor, () -> backend.request(x, z, width).thenCompose(batch -> {
            CompletableFuture<Void> conversion;
            try { conversion = convert.apply(batch); }
            catch (Throwable error) { conversion = CompletableFuture.failedFuture(error); }
            // Cleanup drains before the public future can return pooled data or
            // let a renderer advance its completed frontier.
            return conversion.handle((ignored, error) -> batch.release().thenApply(released -> {
                if (error != null) throw new java.util.concurrent.CompletionException(error);
                return (Void) null;
            })).thenCompose(Function.identity());
        }));
    }

    public boolean isBusy() { return gate.isBusy(); }
    @Override public void close() { LodgenConfig.stopListening(listener); gate.close(); }
}
