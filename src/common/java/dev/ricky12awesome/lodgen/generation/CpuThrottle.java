package dev.ricky12awesome.lodgen.generation;

import dev.ricky12awesome.lodgen.util.Futures;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Pace asynchronous native work using CPU consumed over its entire lifetime,
 * including workers outside the executor that submits the request. */
public final class CpuThrottle {
    private static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private final int processors;
    private final DoubleSupplier target;
    private final IntSupplier limit;
    private final LongSupplier clock, cpuTime;
    private final BiConsumer<Runnable, Long> schedule;
    private final ArrayDeque<Request<?>> waiting = new ArrayDeque<>();
    private int active;
    private long lastTime, lastCpu;
    private double debt, lastFraction;
    private boolean initialized, retryScheduled;

    public CpuThrottle(int processors, DoubleSupplier target, IntSupplier limit) {
        this(processors, target, limit, System::nanoTime, processCpuTime(),
                (task, delay) -> CompletableFuture.delayedExecutor(delay, TimeUnit.NANOSECONDS).execute(task));
    }

    CpuThrottle(int processors, DoubleSupplier target, IntSupplier limit, LongSupplier clock, LongSupplier cpuTime,
                BiConsumer<Runnable, Long> schedule) {
        this.processors = Math.max(1, processors);
        this.target = target;
        this.limit = limit;
        this.clock = clock;
        this.cpuTime = cpuTime;
        this.schedule = schedule;
    }

    private static LongSupplier processCpuTime() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean)
            return bean::getProcessCpuTime;
        var process = ProcessHandle.current();
        return () -> process.info().totalCpuDuration().map(duration -> duration.toNanos()).orElse(-1L);
    }

    public <T> CompletableFuture<T> submit(Executor executor, Supplier<CompletableFuture<T>> start,
                                         BooleanSupplier cancelled) {
        var request = new Request<>(executor, start, cancelled);
        synchronized (this) { waiting.addLast(request); }
        refresh();
        return request.result;
    }

    /** Live load changes and closing pipelines can wake deferred requests now. */
    public void refresh() {
        var rejected = new ArrayList<Request<?>>();
        var ready = new ArrayList<Request<?>>();
        long delay = 0;
        synchronized (this) {
            waiting.removeIf(request -> {
                if (!request.cancelled.getAsBoolean()) return false;
                rejected.add(request);
                return true;
            });
            double fraction = sample();
            int capacity = Math.max(1, limit.getAsInt());
            if (!waiting.isEmpty() && active < capacity && debt > 0 && fraction < 1) {
                if (!retryScheduled) {
                    retryScheduled = true;
                    delay = Math.max(1, Math.min(RECHECK_NANOS, (long) Math.ceil(debt / fraction)));
                }
            } else {
                while (!waiting.isEmpty() && active < capacity && debt <= 0) {
                    active++;
                    ready.add(waiting.removeFirst());
                }
            }
        }
        // Never invoke executors or completion callbacks while holding our lock:
        // each caller also has its own admission gate and shutdown lifecycle.
        rejected.forEach(request -> request.result.completeExceptionally(new CancellationException("Generator closed")));
        ready.forEach(this::launch);
        if (delay > 0) schedule.accept(() -> {
            synchronized (this) { retryScheduled = false; }
            refresh();
        }, delay);
    }

    private double sample() {
        double fraction = target.getAsDouble();
        long now = clock.getAsLong(), cpu = cpuTime.getAsLong();
        if (initialized) {
            long elapsed = Math.max(0, now - lastTime);
            // Process CPU is conservative while LODgen is active. During idle
            // repayment, gameplay/rendering must not prevent all future progress.
            double used = active == 0 ? 0 : cpu < 0 || lastCpu < 0 ? elapsed
                    : Math.max(0, cpu - lastCpu) / (double) processors;
            debt = lastFraction >= 1 ? 0 : debt + used - elapsed * lastFraction;
        }
        if (fraction >= 1) debt = 0;
        // A long idle period can buy at most 50ms of work at the selected rate.
        debt = Math.max(-fraction * RECHECK_NANOS, debt);
        initialized = true;
        lastTime = now;
        lastCpu = cpu;
        lastFraction = fraction;
        return fraction;
    }

    private <T> void launch(Request<T> request) {
        try {
            request.executor.execute(() -> Futures.attempt(() -> {
                if (request.cancelled.getAsBoolean()) throw new CancellationException("Generator closed");
                return request.start.get();
            }).whenComplete((value, error) -> finish(request, value, error)));
        } catch (Throwable error) { finish(request, null, error); }
    }

    private <T> void finish(Request<T> request, T value, Throwable error) {
        synchronized (this) {
            sample();
            active--;
        }
        refresh();
        if (error == null) request.result.complete(value);
        else request.result.completeExceptionally(error);
    }

    private static final class Request<T> {
        final Executor executor;
        final Supplier<CompletableFuture<T>> start;
        final BooleanSupplier cancelled;
        final CompletableFuture<T> result = new CompletableFuture<>() {
            @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
        };

        Request(Executor executor, Supplier<CompletableFuture<T>> start, BooleanSupplier cancelled) {
            this.executor = executor;
            this.start = start;
            this.cancelled = cancelled;
        }
    }
}
