package dev.ricky12awesome.lodgen.integration;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

/** Optional real Chunky API, loaded reflectively so release/test mods don't depend on it. */
public final class ChunkyCheck {
    public record Result(long chunks, long nanos) { }
    public static CompletableFuture<Result> run(int chunkX, int chunkZ, int width) {
        try {
            Object chunky = Class.forName("org.popcraft.chunky.ChunkyProvider").getMethod("get").invoke(null);
            Object api = chunky.getClass().getMethod("getApi").invoke(chunky);
            Class<?> apiType = Class.forName("org.popcraft.chunky.api.ChunkyAPI");
            CompletableFuture<Result> result = new CompletableFuture<>();
            CompletableFuture<Object> taskReady = new CompletableFuture<>();
            long start = System.nanoTime();
            apiType.getMethod("onGenerationComplete", Consumer.class).invoke(api, (Consumer<Object>) event -> {
                // Chunky can fire this event when the iterator is exhausted,
                // while its final asynchronous chunk requests are still active.
                taskReady.thenCompose(task -> drained(task, start)).whenComplete((done, error) -> {
                    if (error != null) result.completeExceptionally(error);
                    else result.complete(done);
                });
            });
            Method startTask = apiType.getMethod("startTask", String.class, String.class, double.class,
                    double.class, double.class, double.class, String.class);
            double radius = width * 8.0;
            boolean started = (Boolean) startTask.invoke(api, "minecraft:overworld", "square",
                    chunkX * 16.0 + radius - 0.5, chunkZ * 16.0 + radius - 0.5,
                    radius - 0.5, radius - 0.5, "concentric");
            if (!started) throw new IllegalStateException("Chunky refused the test task");
            Object task = ((Map<?, ?>) chunky.getClass().getMethod("getGenerationTasks").invoke(chunky)).get("minecraft:overworld");
            if (task == null) throw new IllegalStateException("Chunky task not exposed");
            taskReady.complete(task);
            return result;
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    private static CompletableFuture<Result> drained(Object task, long start) {
        try {
            long count = ((Number) task.getClass().getMethod("getCount").invoke(task)).longValue();
            Object iterator = task.getClass().getMethod("getChunkIterator").invoke(task);
            long total = ((Number) Class.forName("org.popcraft.chunky.iterator.ChunkIterator").getMethod("total").invoke(iterator)).longValue();
            if (count >= total) return CompletableFuture.completedFuture(new Result(count, System.nanoTime() - start));
            return CompletableFuture.supplyAsync(() -> task, CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> drained(task, start));
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }
}
