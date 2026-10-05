package dev.ricky12awesome.lodgen.task;

import dev.ricky12awesome.lodgen.generation.GenerationArea;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TaskTest {
    @Test void firstBatchContainsCenterAndPatchesExpandOutward() {
        for (int radius : new int[]{1, 3, 5, 17, 31, 33, 64}) {
            var area = new GenerationArea(-17, 12345, radius, 0);
            var plan = new SquarePlan(area);
            var first = plan.batch(0);
            assertTrue(area.chunkX() >= first.x() && area.chunkX() < first.x() + first.width());
            assertTrue(area.chunkZ() >= first.z() && area.chunkZ() < first.z() + first.height());
            int minX = area.chunkX() - radius, minZ = area.chunkZ() - radius;
            int patchX = radius / 32, patchZ = radius / 32, previousRing = -1;
            for (long i = 0; i < plan.batches(); i++) {
                var batch = plan.batch(i);
                int ring = Math.max(Math.abs((batch.x() - minX) / 32 - patchX), Math.abs((batch.z() - minZ) / 32 - patchZ));
                assertTrue(ring >= previousRing, "Finish nearer patches before outer rings");
                previousRing = ring;
            }
        }
    }
    @Test void maximumWorldPlanSupportsRandomAccessWithoutEnumeratingTerrain() {
        var plan = new SquarePlan(new GenerationArea(0, 0, GenerationArea.MAX_RADIUS, 0));
        var first = plan.batch(0);
        assertTrue(first.x() <= 0 && first.x() + first.width() > 0);
        assertTrue(first.z() <= 0 && first.z() + first.height() > 0);
        var last = plan.batch(plan.batches() - 1);
        assertEquals(plan.chunks() - last.chunks(), plan.chunksBefore(plan.batches() - 1));
    }
    @Test void compactPatchOrderRetainsExactProgressAcrossPartialEdges() {
        for (int radius : new int[]{1, 3, 5, 17, 31, 33, 64}) {
            for (int center : new int[]{-17, 30_000_000}) {
                var area = new GenerationArea(center, -center, radius, 0);
                var plan = new SquarePlan(area); var seen = new HashSet<Long>();
                long chunks = 0;
                for (long i = 0; i < plan.batches(); i++) {
                    assertEquals(chunks, plan.chunksBefore(i));
                    var batch = plan.batch(i); chunks += batch.chunks();
                    for (int x = batch.x(); x < batch.x() + batch.width(); x++) for (int z = batch.z(); z < batch.z() + batch.height(); z++) {
                        assertTrue(area.lods(x, z));
                        assertTrue(seen.add((x & 0xffffffffL) | (long) z << 32));
                    }
                }
                assertEquals(plan.chunks(), chunks);
                assertEquals(chunks, plan.chunksBefore(plan.batches()));
            }
        }
    }
    @TempDir Path directory;
    @Test void caveModeSurvivesRestartAndOldCheckpointsUseGenerate() throws Exception {
        var area = new GenerationArea(0, 0, 4, 0);
        var progress = new TaskProgress(new SquarePlan(area));
        Path path = directory.resolve("caves/task.toml");
        for (var mode : dev.ricky12awesome.lodgen.generation.CaveMode.values()) {
            var record = new TaskRecord("minecraft:overworld", area, true, false, true, progress.snapshot(), mode);
            TaskStore.write(path, record);
            assertEquals(record, TaskStore.read(path));
        }
        java.nio.file.Files.writeString(path, java.nio.file.Files.readString(path).replaceAll("(?m)^caveMode.*\\R", ""));
        assertEquals(dev.ricky12awesome.lodgen.generation.CaveMode.GENERATE, TaskStore.read(path).caveMode());
    }
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
    @Test void rendererCancellationRetriesUnfinishedBatchesWithoutPausingOrLosingCompletions() {
        var plan = new SquarePlan(new GenerationArea(0, 0, 4, 0));
        var progress = new TaskProgress(plan);
        for (long index = 0; index < plan.batches(); index++) assertEquals(index, progress.next());
        progress.complete(2);
        progress.retry(1); progress.retry(0); progress.retry(2);
        assertEquals(TaskProgress.State.RUNNING, progress.state());
        assertEquals("", progress.error());
        assertEquals(0, progress.next()); progress.complete(0);
        assertEquals(1, progress.next()); progress.complete(1);
        assertEquals(3, progress.next()); progress.complete(3);
        assertEquals(plan.chunks(), progress.completedChunks());
        assertEquals(TaskProgress.State.COMPLETE, progress.state());
        assertThrows(IllegalArgumentException.class, () -> progress.retry(-1));
        assertThrows(IllegalArgumentException.class, () -> progress.retry(plan.batches()));
    }
    @Test void rendererCancellationKeepsCheckpointRunningAndHonorsUserPause() throws Exception {
        var area = new GenerationArea(0, 0, 4, 0);
        var plan = new SquarePlan(area);
        var progress = new TaskProgress(plan);
        progress.next(); progress.next(); progress.complete(1); progress.retry(0);
        Path path = directory.resolve("renderer-shutdown/task.toml");
        TaskStore.write(path, new TaskRecord("minecraft:overworld", area, true, false, true, progress.snapshot()));
        var restored = new TaskProgress(plan, TaskStore.read(path).progress());
        assertEquals(TaskProgress.State.RUNNING, restored.state());
        assertEquals("", restored.error());
        assertEquals(0, restored.next()); restored.complete(0);
        assertEquals(2, restored.next());
        restored.pause(); restored.retry(2);
        assertEquals(TaskProgress.State.PAUSED, restored.state());
        assertEquals(-1, restored.next());
        restored.resume(); assertEquals(2, restored.next());
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
        var draining = new TaskProgress(plan);
        draining.pause();
        for (long i = 0; i < plan.batches(); i++) draining.complete(i);
        assertEquals(TaskProgress.State.PAUSED, draining.state(), "Active completions must not undo a user pause");
        draining.resume(); assertEquals(TaskProgress.State.COMPLETE, draining.state());
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
    @Test void automaticJobsUseTheSameCheckpointAndRetainPausedOrStoppedIntent() throws Exception {
        var area = new GenerationArea(12345, -12345, 5, 1);
        var plan = new SquarePlan(area);
        var progress = new TaskProgress(plan);
        progress.complete(0); progress.pause();
        Path path = directory.resolve("automatic-world/task.toml");
        for (boolean stopped : new boolean[]{false, true}) {
            if (stopped) progress.stop();
            var record = new TaskRecord("minecraft:overworld", area, true, false, true, progress.snapshot());
            TaskStore.write(path, record);
            var restored = TaskStore.read(path);
            assertEquals(record, restored); assertTrue(restored.automatic());
            var resumed = new TaskProgress(plan, restored.progress());
            assertEquals(-1, resumed.next());
            assertEquals(progress.completedChunks(), resumed.completedChunks());
        }
    }
    @Test void shrinkingToCompletedInnerRingsDoesNotNeedTheWholeOuterRadiusComplete() {
        var area = new GenerationArea(0, 0, 17, 1);
        var plan = new SquarePlan(area); var progress = new TaskProgress(plan);
        var inner = new GenerationArea(0, 0, 1, 1);
        assertFalse(progress.completedArea(inner));
        for (long i = 0; i < 64; i++) progress.complete(i); // First complete 32x32 patch.
        assertTrue(progress.completedArea(inner));
        assertEquals(TaskProgress.State.RUNNING, progress.state());
        assertFalse(progress.completedArea(new GenerationArea(0, 0, 18, 0)));
        assertFalse(progress.completedArea(new GenerationArea(0, 0, 1, 2)), "New saved terrain still requires work");
        assertFalse(progress.completedArea(new GenerationArea(16, 16, 1, 1)), "Saved center must fit the previous saved area");
        var world = new SquarePlan(new GenerationArea(0, 0, GenerationArea.MAX_RADIUS, 0));
        assertTrue(world.completedArea(world.batches(), new GenerationArea(0, 0, 64, 0)));
    }
}
