package dev.lodgen.integration;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Direct test callers obey the same live admission check as DH's queue.
 * A completion frees native capacity before the next waiting request starts.
 */
public final class AdmittedRequests implements AutoCloseable {
    private final BooleanSupplier busy;
    private final ArrayDeque<Request<?>> waiting = new ArrayDeque<>();
    private boolean pumping, closed;

    public AdmittedRequests(BooleanSupplier busy) { this.busy = busy; }

    public synchronized <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> start) {
        if (closed) return CompletableFuture.failedFuture(new CancellationException("Test requests closed"));
        var request = new Request<T>(start);
        waiting.addLast(request);
        pump();
        return request.result;
    }
    private synchronized void pump() {
        if (pumping) return;
        pumping = true;
        try {
            while (!closed && !waiting.isEmpty() && !busy.getAsBoolean()) launch(waiting.removeFirst());
        } finally { pumping = false; }
    }
    private <T> void launch(Request<T> request) {
        CompletableFuture<T> work;
        try { work = request.start.get(); }
        catch (Throwable error) { work = CompletableFuture.failedFuture(error); }
        work.whenComplete((value, error) -> {
            if (error == null) request.result.complete(value);
            else request.result.completeExceptionally(error);
            pump();
        });
    }
    @Override public synchronized void close() {
        closed = true;
        while (!waiting.isEmpty()) waiting.removeFirst().result.completeExceptionally(new CancellationException("Test requests closed"));
    }
    private static final class Request<T> {
        final Supplier<CompletableFuture<T>> start;
        // A caller must keep pooled output until native work has drained, just
        // as it does for the production gate's public future.
        final CompletableFuture<T> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };
        Request(Supplier<CompletableFuture<T>> start) { this.start = start; }
    }
}
