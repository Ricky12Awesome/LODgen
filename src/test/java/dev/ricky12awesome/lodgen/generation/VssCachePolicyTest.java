package dev.ricky12awesome.lodgen.generation;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VssCachePolicyTest {
    @TempDir Path directory;

    @Test void unsavedDimensionAndRegionHaveNoSaveTimestamp() throws Exception {
        Path absent = directory.resolve("region/r.-1.-2.mca");
        assertTrue(VssCachePolicy.knownDirectory(absent.getParent()));
        assertTrue(VssCachePolicy.regionCandidate(absent));
        assertEquals(0, VssCachePolicy.regionMtime(absent).toMillis());
        assertArrayEquals(new int[1024], VssCachePolicy.readHeaderTimestamps(absent));
        Files.createDirectories(absent.getParent());
        Files.createFile(absent);
        assertArrayEquals(new int[1024], VssCachePolicy.readHeaderTimestamps(absent));
        assertFalse(VssCachePolicy.knownDirectory(absent), "An existing regular file is not a valid dimension directory");
    }

    @Test void savedSlotsRetainNativeFreshnessWhileAbsentSlotsRemainReusable() throws Exception {
        Path region = directory.resolve("r.-1.-2.mca");
        ByteBuffer bytes = ByteBuffer.allocate(8192);
        int index = (-1 & 31) + ((-33 & 31) << 5);
        bytes.putInt(index * 4, 0x00000201);
        bytes.putInt(4096 + index * 4, 12345);
        bytes.putInt(4096, 54321); // A timestamp without a location is not a saved chunk.
        Files.write(region, bytes.array());
        int[] stamps = VssCachePolicy.readHeaderTimestamps(region);
        assertNotNull(stamps);
        assertEquals(12345, stamps[index]);
        assertEquals(0, stamps[0]);
        assertTrue(stamps[index] >= 12345, "VSS still invalidates same-second saves");
        assertTrue(stamps[index] >= 12344, "VSS still invalidates newer saves");
        assertFalse(stamps[index] >= 12346, "Older saves do not invalidate fresh rows");
        assertFalse(stamps[0] >= 12344, "Never-saved slots retain native rows");
        assertEquals(Files.getLastModifiedTime(region), VssCachePolicy.regionMtime(region));
    }

    @Test void actualCorruptionStillUsesNativeFailSafe() throws Exception {
        Path truncated = directory.resolve("r.0.0.mca");
        Files.write(truncated, new byte[4096]);
        assertNull(VssCachePolicy.readHeaderTimestamps(truncated));
        assertNull(VssCachePolicy.readHeaderTimestamps(directory));
    }
}
