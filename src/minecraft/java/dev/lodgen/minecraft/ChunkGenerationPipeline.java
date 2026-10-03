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
    private final dev.lodgen.generation.CaveMode fixedMode;

    public ChunkGenerationPipeline(Object level) {
        this(level, null);
    }
    public ChunkGenerationPipeline(Object level, dev.lodgen.generation.CaveMode fixedMode) {
        this.fixedMode = fixedMode;
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
        var mode = fixedMode == null ? LodgenConfig.INSTANCE.caveMode() : fixedMode;
        return gate.submit(executor, () -> {
            if (mode == dev.lodgen.generation.CaveMode.GENERATE)
                return backend.request(x, z, width, height, area).thenCompose(batch -> convertAndRelease(batch, convert));
            return requests(x, z, width, height, area, mode).thenCompose(requests -> {
                var conversions = new java.util.ArrayList<CompletableFuture<Void>>();
                var turn = CompletableFuture.<Void>completedFuture(null);
                for (var request : requests) {
                    // Mixed sources write into the same pooled renderer tile.
                    // Serialize conversion, including cleanup after a failure.
                    turn = turn.handle((ignored, error) -> (Void) null)
                            .thenCompose(ignored -> request.thenCompose(batch -> convertAndRelease(batch, convert)));
                    conversions.add(turn);
                }
                return CompletableFuture.allOf(conversions.toArray(CompletableFuture[]::new));
            });
        });
    }

    private CompletableFuture<java.util.List<CompletableFuture<NormalChunkBackend.Batch>>> requests(int x, int z, int width, int height,
            dev.lodgen.generation.GenerationArea area, dev.lodgen.generation.CaveMode mode) {
        return CompletableFuture.supplyAsync(() -> {
            var normal = new java.util.HashSet<Long>();
            for (int cx = x; cx < x + width; cx++) for (int cz = z; cz < z + height; cz++)
                if (area != null && area.saves(cx, cz) || LodGenerationWorld.normalTerrain(level, cx, cz))
                    normal.add((cx & 0xffffffffL) | (long) cz << 32);
            if (normal.size() == width * height) return java.util.List.of(backend.request(x, z, width, height, area));
            var source = LodGenerationWorld.get(level, mode);
            if (source == level) return java.util.List.of(backend.request(x, z, width, height, area));
            var requests = new java.util.ArrayList<CompletableFuture<NormalChunkBackend.Batch>>();
            if (!normal.isEmpty()) requests.add(backend.request(x, z, width, height, area,
                    (cx, cz) -> normal.contains((cx & 0xffffffffL) | (long) cz << 32)));
            if (normal.size() < width * height) requests.add(PersistenceRegistry.backend(source).request(x, z, width, height, null,
                    (cx, cz) -> !normal.contains((cx & 0xffffffffL) | (long) cz << 32)));
            return requests;
        }, level.getServer());
    }

    private CompletableFuture<Void> convertAndRelease(NormalChunkBackend.Batch batch,
            Function<NormalChunkBackend.Batch, CompletableFuture<Void>> convert) {
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
    }

    private void refresh() { gate.reconfigure(dev.lodgen.GenerationSettings.current().batches(), 0); }
    public boolean isBusy() { refresh(); return gate.isBusy(); }
    @Override public void close() { LodgenConfig.stopListening(listener); gate.close(); }
}
