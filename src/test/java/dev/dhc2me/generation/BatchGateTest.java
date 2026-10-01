package dev.dhc2me.generation;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BatchGateTest {
    @Test void boundsRunningAndQueuedWorkWithoutBlockingAWorker() {
        BatchGate gate = new BatchGate(1, 1);
        CompletableFuture<Integer> running = new CompletableFuture<>();
        AtomicInteger started = new AtomicInteger();
        var first = gate.submit(Runnable::run, () -> { started.incrementAndGet(); return running; });
        var second = gate.submit(Runnable::run, () -> { started.incrementAndGet(); return CompletableFuture.completedFuture(2); });
        var third = gate.submit(Runnable::run, () -> CompletableFuture.completedFuture(3));
        assertEquals(1, started.get());
        assertFalse(second.isDone());
        assertTrue(third.isCompletedExceptionally());
        running.complete(1);
        assertEquals(1, first.join());
        assertEquals(2, second.join());
        assertEquals(2, started.get());
    }

    @Test void failureAndExecutorRejectionReleaseTheirPermits() {
        BatchGate gate = new BatchGate(1, 1);
        assertTrue(gate.submit(Runnable::run, () -> { throw new IllegalStateException("start failure"); }).isCompletedExceptionally());
        assertTrue(gate.submit(task -> { throw new java.util.concurrent.RejectedExecutionException(); },
                () -> CompletableFuture.completedFuture(1)).isCompletedExceptionally());
        assertEquals(2, gate.submit(Runnable::run, () -> CompletableFuture.completedFuture(2)).join());
    }

    @Test void closeRejectsQueuedWorkButDrainsActiveWork() {
        BatchGate gate = new BatchGate(1, 1);
        CompletableFuture<Integer> active = new CompletableFuture<>();
        var first = gate.submit(Runnable::run, () -> active);
        var waiting = gate.submit(Runnable::run, () -> fail("Closed gate must never start this request"));
        gate.close();
        assertFalse(first.isDone());
        assertTrue(waiting.isCompletedExceptionally());
        assertTrue(gate.submit(Runnable::run, () -> CompletableFuture.completedFuture(3)).isCompletedExceptionally());
        active.complete(1);
        assertEquals(1, first.join());
    }

    @Test void cancellationCannotRecycleDhDataBeforeNativeWorkersStop() {
        BatchGate gate = new BatchGate(1, 1);
        CompletableFuture<Integer> active = new CompletableFuture<>();
        var result = gate.submit(Runnable::run, () -> active);
        assertFalse(result.cancel(true));
        assertFalse(result.isDone());
        active.complete(7);
        assertEquals(7, result.join());
    }
}
