package dev.lodgen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LodgenConfigTest {
    @TempDir Path directory;

    @Test void createsTomlAndRoundTripsAllSettings() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        assertEquals(LodgenConfig.DEFAULTS, LodgenConfig.load(file));
        var edited = new LodgenConfig(false, 12, 0, false, 64);
        LodgenConfig.write(file, edited);
        assertEquals(edited, LodgenConfig.read(file));
        assertTrue(Files.readString(file).contains("pipelineBatches = 12"));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count(), "No temporary file remains"); }
    }

    @Test void parsesRealTomlAndPreservesUnrelatedTables() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "enabled = true # Inline comment\npipelineBatches = 0x20\nqueuedBatches = 1_024\n[notes]\nvalue = 'keep me'\n");
        assertEquals(new LodgenConfig(true, 32, 1024, true, 0), LodgenConfig.read(file));
        LodgenConfig.write(file, LodgenConfig.DEFAULTS);
        assertTrue(Files.readString(file).contains("keep me"));
    }

    @Test void invalidTypesAndBoundsDisableWithoutRewritingTheBadFile() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        for (String text : new String[]{"enabled = 'false'", "pipelineBatches = 0", "pipelineBatches = 65", "queuedBatches = -1", "queuedBatches = 1.5", "enabled = invalid", "generationDistance = -1", "generationDistance = 1.5", "generationDistance = 2147483648"}) {
            Files.writeString(file, text);
            assertFalse(LodgenConfig.load(file).enabled(), text);
            assertEquals(text, Files.readString(file));
        }
    }
}
