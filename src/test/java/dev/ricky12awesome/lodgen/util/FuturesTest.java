package dev.ricky12awesome.lodgen.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class FuturesTest {
    @Test void aSynchronousSinkFailureStillWaitsForTheOtherSinkToDrain() {
        var running = new CompletableFuture<Void>();
        var failure = new RejectedExecutionException("Renderer executor closed");
        var failed = Futures.<Void>attempt(() -> { throw failure; });
        var combined = CompletableFuture.allOf(running, failed);

        assertFalse(combined.isDone(), "Started work must retain its resources until it finishes");
        running.complete(null);
        var error = assertThrows(CompletionException.class, combined::join);
        assertSame(failure, Futures.rootCause(error));
    }

    @Test void successfulStartsKeepTheirOriginalFutureAndCancellationBehavior() {
        var running = new CompletableFuture<Void>();
        assertSame(running, Futures.attempt(() -> running));
        running.cancel(false);
        assertTrue(Futures.attempt(() -> running).isCancelled());
    }
}
