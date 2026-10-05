package dev.ricky12awesome.lodgen.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationBudgetTest {
    @Test void followsDhCpuFractionsAndMinimalDutyCycle() {
        int[] threads = {4, 8, 16, 24, 32}, batches = {4, 16, 32, 96, 256};
        for (int load = 1; load <= 5; load++) {
            var budget = GenerationBudget.voxy(load, 32, 32L << 30);
            assertEquals(threads[load - 1], budget.threads());
            assertEquals(batches[load - 1], budget.batches());
            assertEquals(load == 1 ? 0.5 : 1.0, budget.runRatio());
        }
    }
    @Test void boundsDependencyWindowByHeapWithoutReducingConversionWorkers() {
        var budget = GenerationBudget.voxy(5, 32, 1L << 30);
        assertEquals(32, budget.threads());
        assertEquals(8, budget.batches());
        assertEquals(1, GenerationBudget.voxy(1, 1, 64L << 20).batches());
        assertEquals(1, GenerationBudget.voxy(1, 1, 64L << 20).threads());
        assertThrows(IllegalArgumentException.class, () -> GenerationBudget.voxy(0, 32, 32L << 30));
    }
}
