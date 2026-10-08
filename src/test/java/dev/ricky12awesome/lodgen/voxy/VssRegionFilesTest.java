package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class VssRegionFilesTest {
    @TempDir Path directory;

    @Test void missingRegionAndMissingChunkSlotDoNotSupersedeGeneratedColumns() throws Exception {
        Path regions = directory.resolve("region");
        long position = packed(2, 3);
        assertFalse(cache(regions).supersedes("minecraft:overworld", position, 100));

        Path mca = regionFile(regions, 0, 0);
        writeHeader(mca, null, 500); // timestamp alone is not a saved chunk; location is empty
        assertFalse(cache(regions).supersedes("minecraft:overworld", position, 100));
    }

    @Test void savedChunkWithEqualOrNewerHeaderTimestampSupersedes() throws Exception {
        Path regions = directory.resolve("region");
        long position = packed(10, 20);
        int slot = slot(10, 20);
        Path mca = regionFile(regions, 0, 0);

        writeHeader(mca, slot, 100);
        assertTrue(cache(regions).supersedes("minecraft:overworld", position, 100), "equal timestamps supersede");

        writeHeader(mca, slot, 101);
        assertTrue(cache(regions).supersedes("minecraft:overworld", position, 100), "newer saved data supersedes");
    }

    @Test void olderSavedChunkDoesNotSupersedeGeneratedColumn() throws Exception {
        Path regions = directory.resolve("region");
        long position = packed(10, 20);
        Path mca = regionFile(regions, 0, 0);
        writeHeader(mca, slot(10, 20), 99);

        assertFalse(cache(regions).supersedes("minecraft:overworld", position, 100));
    }

    @Test void zeroLengthRegionDoesNotSupersedeGeneratedColumn() throws Exception {
        Path regions = directory.resolve("region");
        Path mca = regionFile(regions, 0, 0);
        Files.createFile(mca);

        assertFalse(cache(regions).supersedes("minecraft:overworld", packed(2, 3), 100));
    }

    @Test void negativeCoordinatesUseFloorRegionAndZMajorLocalSlot() throws Exception {
        Path regions = directory.resolve("region");
        int x = -33, z = -2;
        int expectedSlot = slot(x, z); // local x=31, z=30 -> z-major index 991
        assertEquals(991, expectedSlot);

        // A newer-looking chunk in the truncation-toward-zero region is not the target.
        writeHeader(regionFile(regions, -1, -1), expectedSlot, 200);
        assertFalse(cache(regions).supersedes("minecraft:overworld", packed(x, z), 100));

        // Put a timestamp in the transposed (x-major) slot in the correct region.
        int transposedSlot = (x & 31) * 32 + (z & 31);
        assertNotEquals(expectedSlot, transposedSlot);
        writeHeader(regionFile(regions, -2, -1), transposedSlot, 200);
        assertFalse(cache(regions).supersedes("minecraft:overworld", packed(x, z), 100));

        // The exact MCA region and z-major slot do supersede.
        writeHeader(regionFile(regions, -2, -1), expectedSlot, 100);
        assertTrue(cache(regions).supersedes("minecraft:overworld", packed(x, z), 100));
    }

    @Test void unknownDimensionAndUnreadableHeaderFailSafeAsSuperseded() throws Exception {
        var unknown = new VssRegionFiles(dimension -> null);
        assertTrue(unknown.supersedes("minecraft:missing", packed(0, 0), 100));

        Path regions = directory.resolve("region");
        Path mca = regionFile(regions, 0, 0);
        Files.write(mca, new byte[]{1, 2, 3}); // truncated before either complete header
        assertTrue(cache(regions).supersedes("minecraft:overworld", packed(0, 0), 100));
    }

    private VssRegionFiles cache(Path regions) {
        return new VssRegionFiles(dimension -> dimension.equals("minecraft:overworld") ? regions : null);
    }

    private static Path regionFile(Path directory, int regionX, int regionZ) throws Exception {
        Files.createDirectories(directory);
        return directory.resolve("r." + regionX + "." + regionZ + ".mca");
    }

    private static void writeHeader(Path file, Integer slot, int stamp) throws Exception {
        try (var mca = new RandomAccessFile(file.toFile(), "rw")) {
            mca.setLength(8192);
            if (slot != null) {
                mca.seek(slot * 4L);
                mca.writeInt(0x00010001); // non-zero location entry
            }
            mca.seek(4096L + (slot == null ? slot(2, 3) : slot) * 4L);
            mca.writeInt(stamp);
        }
    }

    private static int slot(int x, int z) {
        return (x & 31) + ((z & 31) << 5);
    }

    private static long packed(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }
}
