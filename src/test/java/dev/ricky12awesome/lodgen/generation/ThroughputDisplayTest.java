package dev.ricky12awesome.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ThroughputDisplayTest {
    @Test void holdsRateUntilIntervalAndRefreshesOnLiveChanges() {
        var clock = new AtomicLong();
        var meter = new ChunkThroughput(clock::get);
        var display = new ThroughputDisplay(clock::get);
        meter.completed(50);
        assertTrue(display.refresh(meter, 1000));
        assertFalse(display.refresh(meter, 1000), "Do not resend the action bar every tick");
        assertEquals(10, display.rate());
        meter.completed(50);
        clock.set(999_000_000L);
        assertEquals(10, display.sample(meter, 1000));
        clock.set(1_000_000_000L);
        assertEquals(20, display.sample(meter, 1000));
        meter.completed(50);
        assertEquals(30, display.sample(meter, 250), "Changing the interval refreshes immediately");
        meter.completed(50);
        clock.set(1_249_000_000L);
        assertEquals(30, display.sample(meter, 250));
        clock.set(1_250_000_000L);
        assertEquals(40, display.sample(meter, 250));
        clock.set(7_000_000_000L);
        assertEquals(0, display.sample(meter, 250), "Idle generation eventually displays zero");
    }

    @Test void resetsOnDimensionWorldAndVisibilityChanges() {
        var clock = new AtomicLong();
        var first = new ChunkThroughput(clock::get);
        var second = new ChunkThroughput(clock::get);
        var display = new ThroughputDisplay(clock::get);
        first.completed(50);
        second.completed(100);
        assertEquals(10, display.sample(first, 60000));
        assertEquals(20, display.sample(second, 60000));
        second.completed(50);
        assertEquals(20, display.sample(second, 60000));
        display.clear();
        assertEquals(30, display.sample(second, 60000));
    }
}
