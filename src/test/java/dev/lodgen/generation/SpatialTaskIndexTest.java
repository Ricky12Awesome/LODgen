package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SpatialTaskIndexTest {
    @Test void replacedFinalCandidatesCannotLeaveTheQueueIdle() {
        var tasks = new ConcurrentHashMap<Integer, Object>();
        var index = new SpatialTaskIndex<Integer, Object>();
        for (int i = 0; i < 3; i++) tasks.put(i, new Object());
        java.util.function.Function<java.util.Map.Entry<Integer, Object>, SpatialGenerationOrder.Ranked<Integer>> rank = e -> new SpatialGenerationOrder.Ranked<>(e.getKey(), e.getKey());
        assertEquals(0, index.select(tasks, "area", rank)); tasks.remove(0);
        tasks.put(1, new Object()); tasks.put(2, new Object());
        assertEquals(1, index.select(tasks, "area", rank));
    }
    @Test void sharesOneScanAndRevalidatesReplacedTasksAndBounds() {
        var tasks = new ConcurrentHashMap<Integer, Object>();
        var index = new SpatialTaskIndex<Integer, Object>();
        for (int i = 0; i < 1000; i++) tasks.put(i, new Object());
        var calls = new AtomicInteger();
        java.util.function.Function<java.util.Map.Entry<Integer, Object>, SpatialGenerationOrder.Ranked<Integer>> rank = e -> {
            calls.incrementAndGet(); return e.getKey() == 2 ? null : new SpatialGenerationOrder.Ranked<>(e.getKey(), e.getKey());
        };
        assertEquals(0, index.select(tasks, "area", rank)); tasks.remove(0);
        tasks.put(1, new Object()); // Cached identity must not select the replacement.
        assertEquals(3, index.select(tasks, "area", rank)); tasks.remove(3);
        assertEquals(4, index.select(tasks, "area", rank));
        assertTrue(calls.get() <= 1005, "No whole-map scan for each dispatch");
        assertEquals(1, index.select(tasks, "new center or radius", rank));
    }
    @Test void incorporatesNewTasksAndRefreshesAfterSixtyFourDispatches() {
        var tasks = new ConcurrentHashMap<Integer, Object>();
        var index = new SpatialTaskIndex<Integer, Object>();
        for (int i = 0; i < 100; i++) tasks.put(i, new Object());
        java.util.function.Function<java.util.Map.Entry<Integer, Object>, SpatialGenerationOrder.Ranked<Integer>> rank = e -> new SpatialGenerationOrder.Ranked<>(e.getKey(), e.getKey());
        for (int i = 0; i < 64; i++) { assertEquals(i, index.select(tasks, "area", rank)); tasks.remove(i); }
        tasks.put(-1, new Object()); assertEquals(-1, index.select(tasks, "area", rank)); tasks.remove(-1);
        for (int i = -100; i < -1; i++) tasks.put(i, new Object());
        assertEquals(-100, index.select(tasks, "area", rank));
        index.clear(); tasks.clear(); assertNull(index.select(tasks, "area", rank));
    }
}
