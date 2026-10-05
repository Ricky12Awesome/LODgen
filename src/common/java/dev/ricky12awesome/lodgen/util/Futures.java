package dev.ricky12awesome.lodgen.util;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Keep synchronous startup failures inside the same asynchronous completion path. */
public final class Futures {
    private Futures() {}

    public static <T> CompletableFuture<T> attempt(Supplier<CompletableFuture<T>> start) {
        try { return Objects.requireNonNull(start.get(), "Async operation returned no future"); }
        catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    public static Throwable rootCause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }
}
