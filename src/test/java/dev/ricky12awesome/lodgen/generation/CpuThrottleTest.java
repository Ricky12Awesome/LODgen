package dev.ricky12awesome.lodgen.generation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CpuThrottleTest {
    private static final long MS = 1_000_000L;

    @Test void normalizedCpuQuotaPacesLowMediumAndHighLoadsForAsyncWork() {
        assertPacedAt(0.25, 300 * MS);
        assertPacedAt(0.50, 100 * MS);
        assertPacedAt(0.75, 33_333_334L);
    }

    private static void assertPacedAt(double target, long expectedWait) {
        var harness = new Harness(target, 8);
        var activeWork = new CompletableFuture<Integer>();
        var first = harness.throttle.submit(Runnable::run, () -> activeWork, () -> false);
        harness.clock.addAndGet(100 * MS);
        // 400ms of process CPU across four processors is 100ms of normalized
        // CPU time, even though completion is on another executor.
        harness.cpu.addAndGet(400 * MS);
        var starts = new AtomicLong(-1);
        var next = harness.throttle.submit(Runnable::run, () -> {
            starts.set(harness.clock.get());
            return CompletableFuture.completedFuture(2);
        }, () -> false);

        assertFalse(next.isDone(), "CPU debt should defer the next asynchronous batch");
        activeWork.complete(1);
        harness.scheduler.advanceBy(expectedWait + 2 * MS);

        long actual = starts.get() - 100 * MS;
        assertTrue(actual >= expectedWait - MS,
                () -> "load " + target + " started too early after " + actual + "ns");
        assertTrue(actual <= expectedWait + MS,
                () -> "load " + target + " did not resume near its quota after " + actual + "ns");
        assertEquals(1, first.join());
        assertEquals(2, next.join());
    }

    @Test void overlappingCallersShareTheGlobalActiveLimitUntilNativeWorkDrains() {
        var harness = new Harness(0.25, 1);
        var externalWorker = new CompletableFuture<Integer>();
        var first = harness.throttle.submit(Runnable::run, () -> externalWorker, () -> false);
        var second = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(2), () -> false);

        assertFalse(first.isDone());
        assertFalse(second.isDone());
        externalWorker.complete(1);
        assertEquals(1, first.join());
        assertEquals(2, second.join());
    }

    @Test void idleProcessCpuDoesNotPreventDeferredWorkFromResuming() {
        var harness = new Harness(0.25, 2);
        var activeWork = new CompletableFuture<Integer>();
        harness.throttle.submit(Runnable::run, () -> activeWork, () -> false);
        harness.clock.addAndGet(100 * MS);
        harness.cpu.addAndGet(400 * MS);
        var next = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(2), () -> false);
        assertFalse(next.isDone());

        activeWork.complete(1);
        harness.clock.addAndGet(10_000 * MS);
        harness.cpu.addAndGet(10_000 * MS); // Background process activity while idle.
        harness.throttle.refresh();

        assertEquals(2, next.join(), "idle process CPU must not accrue LODgen debt");
    }

    @Test void idleCreditIsBoundedAndCannotExceedTheSharedActiveLimit() {
        var harness = new Harness(0.25, 1);
        var activeWork = new CompletableFuture<Integer>();
        var nextWork = new CompletableFuture<Integer>();
        harness.throttle.submit(Runnable::run, () -> activeWork, () -> false);
        harness.clock.addAndGet(100 * MS);
        harness.cpu.addAndGet(400 * MS);
        var next = harness.throttle.submit(Runnable::run,
                () -> nextWork, () -> false);
        var afterNext = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(3), () -> false);
        assertFalse(next.isDone());
        assertFalse(afterNext.isDone());

        activeWork.complete(1);
        harness.clock.addAndGet(10_000 * MS);
        harness.cpu.addAndGet(10_000 * MS);
        harness.throttle.refresh();

        assertFalse(next.isDone());
        assertFalse(afterNext.isDone(), "idle credit must not bypass the one-batch active limit");
        // The first queued batch starts, then retains the permit until its
        // external worker completes; the second caller still cannot overlap.
        harness.clock.addAndGet(100 * MS);
        harness.cpu.addAndGet(400 * MS);
        nextWork.complete(2);
        assertEquals(2, next.join());
        assertFalse(afterNext.isDone(), "a long idle period must not buy an oversized CPU burst");
        harness.scheduler.advanceBy(1_000 * MS);
        assertEquals(3, afterNext.join());
    }

    @Test void maximumAndLiveRefreshWakeWaitingRequests() {
        var harness = new Harness(0.25, 1);
        var firstWork = new CompletableFuture<Integer>();
        harness.throttle.submit(Runnable::run, () -> firstWork, () -> false);
        var waiting = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(2), () -> false);
        assertFalse(waiting.isDone());

        harness.limit.set(Integer.MAX_VALUE);
        harness.target.set(1.0);
        harness.throttle.refresh();
        assertEquals(2, waiting.join(), "Maximum and a live refresh should admit immediately");

        firstWork.complete(1);
    }

    @Test void cancellationBeforeStartRejectsQueuedWorkButActiveWorkDrains() {
        var harness = new Harness(0.25, 1);
        var activeWork = new CompletableFuture<Integer>();
        var active = harness.throttle.submit(Runnable::run, () -> activeWork, () -> false);
        var cancelled = new AtomicBoolean();
        var waiting = harness.throttle.submit(Runnable::run,
                () -> fail("cancelled queued work must not start"), cancelled::get);

        cancelled.set(true);
        harness.throttle.refresh();
        assertTrue(waiting.isCompletedExceptionally());
        assertFalse(active.isDone(), "already-started external work must be allowed to drain");
        activeWork.complete(7);
        assertEquals(7, active.join());
    }

    @Test void missingProcessCpuCounterUsesWallTimeFallback() {
        var harness = new Harness(0.25, 2);
        harness.cpu.set(-1);
        var activeWork = new CompletableFuture<Integer>();
        harness.throttle.submit(Runnable::run, () -> activeWork, () -> false);
        harness.clock.addAndGet(100 * MS);
        var next = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(2), () -> false);
        assertFalse(next.isDone(), "fallback should conservatively account for active wall time");

        activeWork.complete(1);
        harness.scheduler.advanceBy(1_000 * MS);
        assertEquals(2, next.join());
    }

    @Test void changingFromMaximumDoesNotChargeThePreviousPeriodAtTheNewTarget() {
        var harness = new Harness(1.0, 2);
        var activeWork = new CompletableFuture<Integer>();
        harness.throttle.submit(Runnable::run, () -> activeWork, () -> false);
        harness.clock.addAndGet(100 * MS);
        harness.cpu.addAndGet(400 * MS);

        harness.target.set(0.25);
        harness.throttle.refresh();
        var next = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(2), () -> false);

        assertEquals(2, next.join(), "the previous Maximum interval must use its original target");
        activeWork.complete(1);
    }

    @Test void startFailureAndExecutorRejectionReleaseTheSharedPermit() {
        var harness = new Harness(1.0, 1);
        var failed = harness.throttle.submit(Runnable::run,
                () -> { throw new IllegalStateException("start failed"); }, () -> false);
        assertTrue(failed.isCompletedExceptionally());

        var rejected = harness.throttle.submit(task -> { throw new RejectedExecutionException(); },
                () -> CompletableFuture.completedFuture(2), () -> false);
        assertTrue(rejected.isCompletedExceptionally());

        var recovered = harness.throttle.submit(Runnable::run,
                () -> CompletableFuture.completedFuture(3), () -> false);
        assertEquals(3, recovered.join());
    }

    private static final class Harness {
        final AtomicLong clock = new AtomicLong();
        final AtomicLong cpu = new AtomicLong();
        final AtomicReference<Double> target;
        final AtomicInteger limit;
        final ManualScheduler scheduler = new ManualScheduler(clock);
        final CpuThrottle throttle;

        Harness(double target, int limit) {
            this.target = new AtomicReference<>(target);
            this.limit = new AtomicInteger(limit);
            throttle = new CpuThrottle(4, this.target::get, this.limit::get,
                    clock::get, cpu::get, scheduler::schedule);
        }
    }

    private static final class ManualScheduler {
        private final AtomicLong clock;
        private final ArrayList<Timer> timers = new ArrayList<>();
        private long sequence;

        ManualScheduler(AtomicLong clock) { this.clock = clock; }

        synchronized void schedule(Runnable task, long delay) {
            timers.add(new Timer(clock.get() + delay, sequence++, task));
        }

        void advanceBy(long duration) {
            long end = clock.get() + duration;
            while (true) {
                Timer next;
                synchronized (this) {
                    next = timers.stream().min(Comparator.comparingLong(Timer::at)
                            .thenComparingLong(Timer::sequence)).orElse(null);
                    if (next == null || next.at > end) {
                        clock.set(end);
                        return;
                    }
                    timers.remove(next);
                }
                clock.set(Math.max(clock.get(), next.at));
                next.task.run();
            }
        }
    }

    private record Timer(long at, long sequence, Runnable task) {}
}
