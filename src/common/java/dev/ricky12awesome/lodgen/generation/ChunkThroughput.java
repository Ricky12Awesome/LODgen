package dev.ricky12awesome.lodgen.generation;

import java.util.Arrays;
import java.util.function.LongSupplier;

/** Bounded, thread-safe five-second rolling counter; unrelated native chunks are excluded. */
public final class ChunkThroughput {
    private static final long BUCKET_NANOS = 100_000_000L;
    private static final int BUCKETS = 50;
    private final long[] epochs = new long[BUCKETS];
    private final long[] counts = new long[BUCKETS];
    private final LongSupplier clock;
    private long totalCompleted;

    public ChunkThroughput() { this(System::nanoTime); }
    public ChunkThroughput(LongSupplier clock) {
        this.clock = clock;
        Arrays.fill(epochs, Long.MIN_VALUE);
    }

    public synchronized void completed(int chunks) {
        if (chunks <= 0) return;
        totalCompleted += chunks;
        long epoch = Math.floorDiv(clock.getAsLong(), BUCKET_NANOS);
        int index = Math.floorMod(epoch, BUCKETS);
        if (epochs[index] != epoch) {
            epochs[index] = epoch;
            counts[index] = 0;
        }
        counts[index] += chunks;
    }

    public synchronized long totalCompleted() { return totalCompleted; }

    public synchronized double chunksPerSecond() {
        long now = Math.floorDiv(clock.getAsLong(), BUCKET_NANOS);
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (epochs[i] <= now && epochs[i] > now - BUCKETS) total += counts[i];
        }
        return total / 5.0;
    }
}
