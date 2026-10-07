package dev.ricky12awesome.lodgen.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationBudgetTest {
    @Test void keepsMinimumSmallAndMaximumAtFullCapacity() {
        int[] threads = {1, 8, 16, 24, 32}, batches = {1, 8, 16, 24, 256};
        for (int load = 1; load <= 5; load++) {
            var budget = GenerationBudget.forCpuLoad(load, 32, 32L << 30);
            assertEquals(threads[load - 1], budget.threads());
            assertEquals(batches[load - 1], budget.batches());
            assertEquals(load == 1 ? 0.32 : 1.0, budget.runRatio());
        }
    }

    @Test void fractionalBudgetsAccountForRoundingOnSmallAndOddCpus() {
        for (int processors : new int[]{1, 2, 3, 6, 10, 32}) {
            for (int load = 1; load <= 5; load++) {
                var budget = GenerationBudget.forCpuLoad(load, processors, 32L << 30);
                assertEquals(GenerationBudget.cpuFraction(load), budget.threads() * budget.runRatio() / processors, 1e-10);
            }
        }
        assertEquals(1, GenerationBudget.forCpuLoad(1, 256, 32L << 30).threads());
        assertEquals(1, GenerationBudget.forCpuLoad(1, 256, 32L << 30).batches());
    }
    @Test void boundsDependencyWindowByHeapWithoutReducingConversionWorkers() {
        var budget = GenerationBudget.forCpuLoad(5, 32, 1L << 30);
        assertEquals(32, budget.threads());
        assertEquals(8, budget.batches());
        assertEquals(1, GenerationBudget.forCpuLoad(1, 1, 64L << 20).batches());
        assertEquals(1, GenerationBudget.forCpuLoad(1, 1, 64L << 20).threads());
        assertThrows(IllegalArgumentException.class, () -> GenerationBudget.forCpuLoad(0, 32, 32L << 30));
    }
}
