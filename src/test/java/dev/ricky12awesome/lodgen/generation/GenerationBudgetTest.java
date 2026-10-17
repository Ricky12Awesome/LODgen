package dev.ricky12awesome.lodgen.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GenerationBudgetTest {
    @Test void usesTheSelectedThreadsAtFullDutyCycleOnSmallAndOddCpus() {
        for (int processors : new int[]{1, 2, 3, 6, 10, 32}) {
            for (int threads = 1; threads <= processors; threads++) {
                var budget = GenerationBudget.forThreads(threads, processors, 32L << 30);
                assertEquals(threads, budget.threads());
                assertEquals(1, budget.runRatio());
                assertTrue(budget.batches() >= threads);
            }
        }
        assertEquals(1, GenerationBudget.forThreads(1, 256, 32L << 30).batches());
        assertEquals(256, GenerationBudget.forThreads(32, 32, 32L << 30).batches());
    }

    @Test void boundsDependencyWindowByHeapAndThreadsBySystemCapacity() {
        var budget = GenerationBudget.forThreads(32, 32, 1L << 30);
        assertEquals(32, budget.threads());
        assertEquals(8, budget.batches());
        assertEquals(1, GenerationBudget.forThreads(1, 1, 64L << 20).batches());
        assertEquals(4, GenerationBudget.forThreads(32, 4, 1L << 30).threads());
    }

    @Test void lagProtectionShrinksAdmissionWindowWithoutChangingSelectedWorkers() {
        var normal = GenerationBudget.of(32, 1, 32, 1L << 30);
        var slow = GenerationBudget.of(32, 0.25, 32, 1L << 30);
        assertEquals(normal.threads(), slow.threads());
        assertEquals(0.25, slow.runRatio());
        assertEquals(2, slow.batches());
        assertEquals(1, GenerationBudget.of(1, 0.05, 32, 64L << 20).batches());
    }
}
