package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import static dev.lodgen.generation.GenerationProgress.State.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationProgressTest {
    @Test void estimatesRemainingWorkWithCeilingAndChangingRate() {
        var progress = new GenerationProgress(64, 1001, RUNNING);
        assertEquals(11, progress.estimatedSeconds(100));
        assertEquals(6, progress.estimatedSeconds(200));
        assertEquals(0, new GenerationProgress(64, 0, COMPLETE).estimatedSeconds(0));
    }

    @Test void pausedStoppedWaitingAndUnavailableRatesHaveNoEta() {
        for (var state : new GenerationProgress.State[]{PAUSED, STOPPED, WAITING_FOR_RENDERER, DISABLED})
            assertEquals(-1, new GenerationProgress(64, 1000, state).estimatedSeconds(100));
        assertEquals(-1, new GenerationProgress(64, -1, RUNNING).estimatedSeconds(100));
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY})
            assertEquals(-1, new GenerationProgress(64, 1000, RUNNING).estimatedSeconds(rate));
    }

    @Test void formatsShortAndLongDurationsWithoutWrappingHours() {
        assertEquals("—", GenerationProgress.duration(-1));
        assertEquals("0s", GenerationProgress.duration(0));
        assertEquals("59s", GenerationProgress.duration(59));
        assertEquals("1m 1s", GenerationProgress.duration(61));
        assertEquals("1h 1m", GenerationProgress.duration(3661));
        assertEquals("2d 1h", GenerationProgress.duration(176400));
    }
}
