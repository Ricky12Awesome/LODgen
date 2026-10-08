package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VssColumnsTest {
    @TempDir Path directory;

    @Test void columnsPersistWithoutBackingRegionFilesAndDistinguishAirFromMissing() throws Exception {
        Path database = directory.resolve("vss-lods.sqlite");
        long position = position(12, -7);
        try (var columns = new VssColumns(source(database), "identity-v1")) {
            columns.put(List.of(
                    new VssColumns.Column("minecraft:overworld", position, new byte[0], 0, 100),
                    new VssColumns.Column("minecraft:the_nether", position, new byte[]{4, 5, 6}, 19, 101)));

            var air = columns.get("minecraft:overworld", position);
            assertNotNull(air);
            assertEquals(0, air.size());
            assertArrayEquals(new byte[0], air.frame());
            assertNull(columns.get("minecraft:overworld", position(13, -7)), "missing must not mean all-air");
        }

        try (var files = Files.walk(directory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".mca")),
                    "the cache is independent of MCA files");
        }
        try (var reopened = new VssColumns(source(database), "identity-v1")) {
            var air = reopened.get("minecraft:overworld", position);
            assertNotNull(air);
            assertEquals(0, air.size());
            assertEquals(100, air.stamp());
            assertArrayEquals(new byte[0], air.frame());
            var data = reopened.get("minecraft:the_nether", position);
            assertNotNull(data);
            assertEquals(19, data.size());
            assertEquals(101, data.stamp());
            assertArrayEquals(new byte[]{4, 5, 6}, data.frame());
        }
    }

    @Test void newerTimestampWinsAndDimensionsUseSeparateKeys() throws Exception {
        long position = position(4, 9);
        try (var columns = new VssColumns(source(directory.resolve("columns.sqlite")), "identity-v1")) {
            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position, new byte[]{1}, 4, 200)));
            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position, new byte[]{2}, 4, 199)));
            columns.put(List.of(new VssColumns.Column("minecraft:the_nether", position, new byte[]{3}, 4, 150)));

            assertArrayEquals(new byte[]{1}, columns.get("minecraft:overworld", position).frame());
            assertEquals(200, columns.get("minecraft:overworld", position).stamp());
            assertArrayEquals(new byte[]{3}, columns.get("minecraft:the_nether", position).frame());

            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position, new byte[]{4}, 4, 201)));
            assertArrayEquals(new byte[]{4}, columns.get("minecraft:overworld", position).frame());
        }
    }

    @Test void invalidationAndClearRemoveColumns() throws Exception {
        long first = position(1, 2), second = position(3, 4);
        try (var columns = new VssColumns(source(directory.resolve("columns.sqlite")), "identity-v1")) {
            columns.put(List.of(
                    new VssColumns.Column("minecraft:overworld", first, new byte[]{1}, 1, 10),
                    new VssColumns.Column("minecraft:overworld", second, new byte[]{2}, 1, 10),
                    new VssColumns.Column("minecraft:the_nether", first, new byte[]{3}, 1, 10)));

            columns.delete("minecraft:overworld", new long[]{first});
            assertNull(columns.get("minecraft:overworld", first));
            assertNotNull(columns.get("minecraft:overworld", second));
            assertNotNull(columns.get("minecraft:the_nether", first));

            columns.clear();
            assertNull(columns.get("minecraft:overworld", second));
            assertNull(columns.get("minecraft:the_nether", first));
        }
    }

    @Test void identityChangeDropsPriorColumns() throws Exception {
        Path database = directory.resolve("columns.sqlite");
        long position = position(8, 8);
        try (var columns = new VssColumns(source(database), "registry-a|mask-a|wire-v20")) {
            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position, new byte[]{7}, 1, 10)));
        }
        try (var changed = new VssColumns(source(database), "registry-b|mask-a|wire-v20")) {
            assertNull(changed.get("minecraft:overworld", position));
        }
    }

    @Test void delayedWriteCannotResurrectAColumnAfterDelete() throws Exception {
        long position = position(-2, 6);
        try (var columns = new VssColumns(source(directory.resolve("columns.sqlite")), "identity-v1")) {
            long revision = columns.acquire("minecraft:overworld", position);
            columns.delete("minecraft:overworld", new long[]{position});

            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position,
                    new byte[]{9}, 1, 50, revision)));
            columns.release("minecraft:overworld", position, revision);
            assertNull(columns.get("minecraft:overworld", position));
        }
    }

    @Test void currentRevisionCanCommitAndRemainReadableAfterRelease() throws Exception {
        long position = position(0, 0);
        try (var columns = new VssColumns(source(directory.resolve("columns.sqlite")), "identity-v1")) {
            long revision = columns.acquire("minecraft:overworld", position);
            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position,
                    new byte[]{2}, 1, 101, revision)));
            columns.release("minecraft:overworld", position, revision);
            assertArrayEquals(new byte[]{2}, columns.get("minecraft:overworld", position).frame());
        }
    }

    @Test void clearInvalidatesAnInFlightAcquisition() throws Exception {
        long position = position(5, 5);
        try (var columns = new VssColumns(source(directory.resolve("columns.sqlite")), "identity-v1")) {
            long revision = columns.acquire("minecraft:overworld", position);
            columns.clear();
            columns.put(List.of(new VssColumns.Column("minecraft:overworld", position,
                    new byte[]{3}, 1, 1, revision)));
            columns.release("minecraft:overworld", position, revision);
            assertNull(columns.get("minecraft:overworld", position));
        }
    }

    private static SQLiteDataSource source(Path database) {
        var source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + database.toAbsolutePath());
        return source;
    }

    private static long position(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }
}
