package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkThroughputTest {
    @Test void averagesCompletionsAndExpiresAfterFiveSeconds() {
        AtomicLong clock = new AtomicLong();
        var meter = new ChunkThroughput(clock::get);
        assertEquals(0, meter.chunksPerSecond());
        meter.completed(50);
        meter.completed(0);
        meter.completed(-1);
        assertEquals(10, meter.chunksPerSecond());
        clock.set(4_900_000_000L);
        meter.completed(100);
        assertEquals(30, meter.chunksPerSecond());
        clock.set(5_000_000_000L);
        assertEquals(20, meter.chunksPerSecond());
        clock.set(9_900_000_000L);
        assertEquals(0, meter.chunksPerSecond());
        meter.completed(25); // Reuse the ring after idle time without reviving stale counts.
        assertEquals(5, meter.chunksPerSecond());
    }
}
