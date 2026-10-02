package dev.lodgen;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Conversion workers follow live CPU load, rather than a fixed eight-thread cap. */
public final class GenerationWorkers extends ThreadPoolExecutor {
    private final ThreadLocal<Long> started = new ThreadLocal<>();
    public GenerationWorkers(String name) {
        super(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            var thread = new Thread(task, name); thread.setDaemon(true); return thread;
        });
        allowCoreThreadTimeOut(true);
    }
    @Override public void execute(Runnable command) {
        resize();
        super.execute(command);
    }
    private synchronized void resize() {
        int threads = GenerationSettings.current().threads();
        if (threads > getMaximumPoolSize()) { setMaximumPoolSize(threads); setCorePoolSize(threads); }
        else if (threads < getCorePoolSize()) { setCorePoolSize(threads); setMaximumPoolSize(threads); }
    }
    @Override protected void beforeExecute(Thread thread, Runnable command) { started.set(System.nanoTime()); }
    @Override protected void afterExecute(Runnable command, Throwable error) {
        double ratio = GenerationSettings.current().runRatio();
        long elapsed = System.nanoTime() - started.get(); started.remove();
        if (ratio < 1 && !isShutdown()) LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(50), (long) (elapsed * (1 / ratio - 1))));
    }
}
