package dev.ricky12awesome.lodgen.generation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenerationLagTest {
    private static final long MS = 1_000_000L;

    @Test void healthyTicksAndMinorJitterKeepFullSpeed() {
        var lag = new GenerationLag();
        var server = new Object();
        for (int i = 0; i < 100; i++) lag.record(server, (i % 2 == 0 ? 49 : 51) * MS);
        assertEquals(1, lag.ratio());
    }

    @Test void sustainedSlowTicksThrottleAndHealthyTicksRecoverGradually() {
        var lag = new GenerationLag();
        var server = new Object();
        for (int i = 0; i < 20; i++) lag.record(server, 100 * MS);
        assertEquals(0.05, lag.ratio());
        lag.record(server, 5 * MS);
        assertTrue(lag.ratio() < 1, "one fast tick must not immediately restore all generation work");
        for (int i = 0; i < 100; i++) lag.record(server, 5 * MS);
        assertEquals(1, lag.ratio());
    }

    @Test void normalTickSchedulingJitterStillAllowsRecovery() {
        var lag = new GenerationLag();
        var server = new Object();
        for (int i = 0; i < 10; i++) lag.record(server, 100 * MS);
        for (int i = 0; i < 120; i++) lag.record(server, 51 * MS);
        assertEquals(1, lag.ratio());
    }

    @Test void stoppingServerRemovesItsLagAndPreservesOtherServers() {
        var lag = new GenerationLag();
        var slow = new Object();
        var healthy = new Object();
        lag.record(slow, 250 * MS);
        double ratio = lag.ratio();
        lag.record(healthy, 5 * MS);
        assertEquals(ratio, lag.ratio());
        lag.remove(healthy);
        assertEquals(ratio, lag.ratio());
        lag.remove(slow);
        assertEquals(1, lag.ratio());
    }
}
