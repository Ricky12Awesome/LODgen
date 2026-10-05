package dev.ricky12awesome.lodgen.generation;

import java.util.function.LongSupplier;

/** Client-thread cache that refreshes immediately after source or interval changes. */
public final class ThroughputDisplay {
    private final LongSupplier clock;
    private ChunkThroughput source;
    private int intervalMs;
    private long sampledAt;
    private double rate;

    public ThroughputDisplay() { this(System::nanoTime); }
    public ThroughputDisplay(LongSupplier clock) { this.clock = clock; }

    public double sample(ChunkThroughput meter, int updateIntervalMs) {
        refresh(meter, updateIntervalMs);
        return rate;
    }

    public double rate() { return rate; }

    public boolean refresh(ChunkThroughput meter, int updateIntervalMs) {
        long now = clock.getAsLong();
        if (source != meter || intervalMs != updateIntervalMs || now - sampledAt >= updateIntervalMs * 1_000_000L) {
            source = meter;
            intervalMs = updateIntervalMs;
            sampledAt = now;
            rate = meter.chunksPerSecond();
            return true;
        }
        return false;
    }

    public void clear() { source = null; }
}
