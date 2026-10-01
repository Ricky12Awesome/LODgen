package dev.lodgen.task;

import dev.lodgen.generation.GenerationArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TaskTest {
    @TempDir Path directory;
    @Test void commandExamplesAndRoundUpUseTheSameChunkRadii() {
        assertEquals(512, RadiusParser.chunks("512c"));
        assertEquals(512, RadiusParser.chunks("8192"));
        assertEquals(512, RadiusParser.chunks("8190"));
        assertEquals(128, RadiusParser.chunks("128c"));
        assertEquals(128, RadiusParser.chunks("2048"));
        assertEquals(128, RadiusParser.chunks("2040"));
        assertEquals(1, RadiusParser.chunks("1"));
        assertEquals(0, RadiusParser.chunks("0"));
        assertEquals(64, RadiusParser.chunks("64c"));
        for (String invalid : List.of("-1", "1.5", "c", "1cc", "64 c", "9223372036854775808", "3750001c"))
            assertThrows(IllegalArgumentException.class, () -> RadiusParser.chunks(invalid), invalid);
    }
    @Test void exactSquaresAndSavedSubsetsIncludeNegativeChunkCoordinates() {
        var area = new GenerationArea(-1, -17, 3, 1);
        var plan = new SquarePlan(area);
        var seen = new HashSet<Long>();
        int saved = 0;
        for (long i = 0; i < plan.batches(); i++) {
            var batch = plan.batch(i);
            assertTrue(batch.chunks() <= 16);
            for (int x = batch.x(); x < batch.x() + batch.width(); x++) for (int z = batch.z(); z < batch.z() + batch.height(); z++) {
                assertTrue(area.lods(x, z));
                assertTrue(seen.add((x & 0xffffffffL) | (long) z << 32), "No overlapping native targets");
                if (area.saves(x, z)) saved++;
            }
        }
        assertEquals(36, seen.size()); assertEquals(4, saved);
        assertFalse(new GenerationArea(0, 0, 1, 0).saves(0, 0));
        var largerSaved = new SquarePlan(new GenerationArea(0, 0, 1, 2));
        assertEquals(16, largerSaved.chunks());
    }
    @Test void small64ChunkRadiusPlanDoesNotGenerateOrAllocateChunkData() {
        var plan = new SquarePlan(new GenerationArea(0, 0, 64, 0));
        assertEquals(16384, plan.chunks()); assertEquals(1024, plan.batches());
        assertEquals(0, plan.chunksBefore(0));
        assertEquals(16384, plan.chunksBefore(plan.batches()));
        var clipped = new SquarePlan(new GenerationArea(30_000_000, -30_000_000, 1, 0));
        assertEquals(1, clipped.chunks());
    }
    @Test void restartRetriesIncompleteBatchesAndKeepsOutOfOrderCompletions() {
        var plan = new SquarePlan(new GenerationArea(0, 0, 4, 1));
        var original = new TaskProgress(plan);
        assertEquals(0, original.next()); assertEquals(1, original.next()); assertEquals(2, original.next());
        original.complete(2);
        var resumed = new TaskProgress(plan, original.snapshot());
        assertEquals(16, resumed.completedChunks());
        assertEquals(0, resumed.next()); resumed.complete(0);
        assertEquals(1, resumed.next()); resumed.complete(1);
        assertEquals(3, resumed.next()); resumed.complete(3);
        assertEquals(64, resumed.completedChunks());
        assertEquals(TaskProgress.State.COMPLETE, resumed.state());
        assertEquals(-1, resumed.next());
    }
    @Test void pauseStopAndLiveDrainPreserveTaskIntent() {
        var plan = new SquarePlan(new GenerationArea(0, 0, 4, 0));
        var progress = new TaskProgress(plan);
        long index = progress.next(); progress.pause(); progress.complete(index);
        assertEquals(-1, progress.next());
        var restored = new TaskProgress(plan, progress.snapshot());
        assertEquals(TaskProgress.State.PAUSED, restored.state());
        restored.resume(); assertEquals(1, restored.next());
        restored.stop(); restored.complete(1);
        assertEquals(TaskProgress.State.STOPPED, restored.state());
        assertEquals(-1, restored.next());
        assertThrows(IllegalStateException.class, restored::resume);
    }
    @Test void tomlCheckpointRoundTripsAndRejectsCorruptProgress() throws Exception {
        var area = new GenerationArea(12345, -12345, 4, 1);
        var plan = new SquarePlan(area); var progress = new TaskProgress(plan);
        progress.complete(2);
        var record = new TaskRecord("minecraft:overworld", area, true, false, progress.snapshot());
        Path path = directory.resolve("lodgen/task.toml");
        TaskStore.write(path, record);
        assertEquals(record, TaskStore.read(path));
        assertNull(TaskStore.read(directory.resolve("missing.toml")));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgress(plan, new TaskProgress.Snapshot(TaskProgress.State.RUNNING, 1, List.of(1L), "")));
        assertThrows(IllegalArgumentException.class, () -> new TaskProgress(plan, new TaskProgress.Snapshot(TaskProgress.State.COMPLETE, 1, List.of(), "")));
    }
}
