package dev.ricky12awesome.lodgen.generation;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Reduce generation duty cycles during slow server ticks, then recover gradually. */
public final class GenerationLag {
    private static final double TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private final Map<Object, Sample> servers = new IdentityHashMap<>();
    private volatile double ratio = 1;

    public synchronized void record(Object server, long elapsedNanos) {
        if (elapsedNanos <= 0) return;
        var sample = servers.computeIfAbsent(server, ignored -> new Sample());
        sample.average += (elapsedNanos - sample.average) * 0.2;
        // A little tolerance prevents normal tick jitter from lowering throughput.
        if (sample.average > TICK_NANOS * 1.05)
            sample.ratio = Math.max(0.05, sample.ratio * TICK_NANOS / sample.average);
        else if (sample.average <= TICK_NANOS * 1.025)
            sample.ratio = Math.min(1, sample.ratio + 0.02);
        refresh();
    }

    public synchronized void remove(Object server) {
        servers.remove(server);
        refresh();
    }

    private void refresh() {
        ratio = servers.values().stream().mapToDouble(sample -> sample.ratio).min().orElse(1);
    }

    public double ratio() { return ratio; }

    private static final class Sample {
        double average = TICK_NANOS;
        double ratio = 1;
    }
}
