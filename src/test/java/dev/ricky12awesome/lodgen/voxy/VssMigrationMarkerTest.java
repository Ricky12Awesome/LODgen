package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class VssMigrationMarkerTest {
    @TempDir Path directory;
    @Test void completedReceiptPreservesSourceAndPreventsImportAfterNativeInvalidation() throws Exception {
        Path source = directory.resolve("vss-lods.sqlite"), marker = directory.resolve("vss-native-migration");
        byte[] original = new byte[]{1, 2, 3}; Files.write(source, original);
        assertFalse(VssMigrationMarker.completed(marker, source));
        VssMigrationMarker.complete(marker, source, VssMigrationMarker.source(source), "old-privacy-identity", 28_000);
        assertTrue(VssMigrationMarker.completed(marker, source));
        assertArrayEquals(original, Files.readAllBytes(source));
        // Native cache rows and current privacy are deliberately not marker inputs:
        // an invalidated row must never be resurrected from the old source.
        Files.write(directory.resolve("native.sqlite"), new byte[0]);
        assertTrue(VssMigrationMarker.completed(marker, source));
        try (var entries = Files.list(directory)) { assertEquals(3, entries.count()); }
    }
    @Test void changedSourceAndIncompleteReceiptsNeverCompleteCutover() throws Exception {
        Path source = directory.resolve("vss-lods.sqlite"), marker = directory.resolve("vss-native-migration");
        Files.write(source, new byte[]{1});
        var original = VssMigrationMarker.source(source);
        Files.write(source, new byte[]{1, 2});
        assertThrows(java.io.IOException.class, () -> VssMigrationMarker.complete(marker, source, original, "identity", 1));
        assertFalse(Files.exists(marker));
        Files.writeString(marker, "version=1\nidentity=identity\n");
        assertFalse(VssMigrationMarker.completed(marker, source));
    }
    @Test void emptyWalCreatedByReadonlySqliteDoesNotChangeSourceIdentity() throws Exception {
        Path source = directory.resolve("vss-lods.sqlite"), marker = directory.resolve("vss-native-migration");
        Files.write(source, new byte[]{1});
        var absentWal = VssMigrationMarker.source(source);
        Path wal = source.resolveSibling("vss-lods.sqlite-wal");
        Files.write(wal, new byte[0]);
        Files.write(source.resolveSibling("vss-lods.sqlite-shm"), new byte[32768]);
        assertEquals(absentWal, VssMigrationMarker.source(source));
        VssMigrationMarker.complete(marker, source, absentWal, "identity", 1);
        assertTrue(VssMigrationMarker.completed(marker, source));
        Files.delete(wal);
        assertTrue(VssMigrationMarker.completed(marker, source));
        Files.write(wal, new byte[]{2});
        assertFalse(VssMigrationMarker.completed(marker, source));
    }
    @Test void changedLegacyWalInvalidatesReceipt() throws Exception {
        Path source = directory.resolve("vss-lods.sqlite"), marker = directory.resolve("vss-native-migration");
        Files.write(source, new byte[]{1});
        VssMigrationMarker.complete(marker, source, VssMigrationMarker.source(source), "identity", 1);
        Files.write(source.resolveSibling("vss-lods.sqlite-wal"), new byte[]{2});
        assertFalse(VssMigrationMarker.completed(marker, source));
    }
}
