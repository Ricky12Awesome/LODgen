package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.generation.BatchGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AdmittedRequestsTest {
    @Test void threeRequestsFinishWithOnlyOneOrTwoNativeSlots() {
        for (int slots : new int[]{1, 2}) {
            var gate = new BatchGate(slots, 0);
            try (var caller = new AdmittedRequests(gate::isBusy)) {
                var started = new AtomicInteger();
                var work = List.of(new CompletableFuture<Integer>(), new CompletableFuture<Integer>(), new CompletableFuture<Integer>());
                var results = new ArrayList<CompletableFuture<Integer>>();
                for (int i = 0; i < 3; i++) {
                    int index = i;
                    results.add(caller.submit(() -> gate.submit(Runnable::run, () -> { started.incrementAndGet(); return work.get(index); })));
                }
                assertEquals(slots, started.get());
                assertFalse(results.get(2).isDone(), "The third request waits rather than hitting the full native gate");
                for (int i = 0; i < 3; i++) {
                    work.get(i).complete(i);
                    assertEquals(i, results.get(i).join());
                }
                assertEquals(3, started.get());
            }
        }
    }
    @Test void waitingRequestsHonorLoweredLiveCapacity() {
        var gate = new BatchGate(2, 0);
        try (var caller = new AdmittedRequests(gate::isBusy)) {
            var one = new CompletableFuture<Integer>(); var two = new CompletableFuture<Integer>();
            caller.submit(() -> gate.submit(Runnable::run, () -> one));
            caller.submit(() -> gate.submit(Runnable::run, () -> two));
            var three = caller.submit(() -> gate.submit(Runnable::run, () -> CompletableFuture.completedFuture(3)));
            gate.reconfigure(1, 0);
            one.complete(1);
            assertFalse(three.isDone(), "The remaining active request still fills the lowered limit");
            two.complete(2);
            assertEquals(3, three.join());
        }
    }
    @Test void failureFreesCapacityAndCloseCancelsOnlyUnstartedRequests() {
        var gate = new BatchGate(1, 0);
        var caller = new AdmittedRequests(gate::isBusy);
        var work = new CompletableFuture<Integer>();
        var first = caller.submit(() -> gate.submit(Runnable::run, () -> work));
        var rejected = caller.submit(() -> gate.submit(task -> { throw new RejectedExecutionException("Executor closed"); },
                () -> CompletableFuture.completedFuture(2)));
        var next = caller.submit(() -> gate.submit(Runnable::run, () -> CompletableFuture.completedFuture(3)));
        work.completeExceptionally(new IllegalStateException("Native generation failed"));
        assertTrue(first.isCompletedExceptionally()); assertTrue(rejected.isCompletedExceptionally());
        assertEquals(3, next.join());
        var draining = new CompletableFuture<Integer>();
        var active = caller.submit(() -> gate.submit(Runnable::run, () -> draining));
        var waiting = caller.submit(() -> fail("Closed caller must not start waiting work"));
        caller.close();
        assertTrue(waiting.isCompletedExceptionally()); assertFalse(active.isDone());
        assertFalse(active.cancel(true), "Pooled data must remain owned until native work drains");
        draining.complete(4); assertEquals(4, active.join());
    }
}
