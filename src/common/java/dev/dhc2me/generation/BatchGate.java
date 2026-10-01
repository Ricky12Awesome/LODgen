package dev.dhc2me.generation;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Nonblocking admission control. Closing drains active work and rejects queued work. */
public final class BatchGate implements AutoCloseable {
    private final int limit;
    private final int queueLimit;
    private final ArrayDeque<Request<?>> waiting = new ArrayDeque<>();
    private int active;
    private boolean closed;

    public BatchGate(int limit, int queueLimit) {
        if (limit < 1 || queueLimit < 0) throw new IllegalArgumentException("Invalid limits");
        this.limit = limit;
        this.queueLimit = queueLimit;
    }

    public synchronized <T> CompletableFuture<T> submit(Executor executor, Supplier<CompletableFuture<T>> start) {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Generator closed"));
        if (active >= limit && waiting.size() >= queueLimit) {
            return CompletableFuture.failedFuture(new java.util.concurrent.RejectedExecutionException("LOD generation queue full"));
        }
        Request<T> request = new Request<>(executor, start);
        if (active < limit) {
            active++;
            launch(request);
        } else {
            waiting.addLast(request);
        }
        return request.result;
    }

    private <T> void launch(Request<T> request) {
        try {
            request.executor.execute(() -> {
                CompletableFuture<T> work;
                try {
                    synchronized (this) {
                        if (closed) throw new CancellationException("Generator closed");
                    }
                    work = request.start.get();
                } catch (Throwable error) {
                    work = CompletableFuture.failedFuture(error);
                }
                work.whenComplete((value, error) -> {
                    if (error == null) request.result.complete(value);
                    else request.result.completeExceptionally(error);
                    finished();
                });
            });
        } catch (Throwable error) {
            request.result.completeExceptionally(error);
            finished();
        }
    }

    private synchronized void finished() {
        active--;
        if (!closed && !waiting.isEmpty()) {
            Request<?> request = waiting.removeFirst();
            active++;
            launch(request);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        while (!waiting.isEmpty()) {
            waiting.removeFirst().result.completeExceptionally(new CancellationException("Generator closed"));
        }
    }

    private static final class Request<T> {
        private final Executor executor;
        private final Supplier<CompletableFuture<T>> start;
        // DH owns pooled data until this future completes. CompletableFuture.cancel()
        // normally completes immediately, even when native workers are still running.
        // Shutdown uses the generator's cancellation flag and lets those workers drain.
        private final CompletableFuture<T> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };

        private Request(Executor executor, Supplier<CompletableFuture<T>> start) {
            this.executor = executor;
            this.start = start;
        }
    }
}
