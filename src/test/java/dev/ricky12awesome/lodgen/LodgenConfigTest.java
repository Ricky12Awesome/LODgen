package dev.ricky12awesome.lodgen;

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
        var edited = new LodgenConfig(false, 5, 64, true, 250, dev.ricky12awesome.lodgen.generation.GenerationCenter.CUSTOM, 1234, -5678, 2, dev.ricky12awesome.lodgen.generation.CaveMode.EMPTY);
        LodgenConfig.write(file, edited);
        assertEquals(edited, LodgenConfig.read(file));
        assertTrue(Files.readString(file).contains("cpuLoad = 5"));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count(), "No temporary file remains"); }
    }

    @Test void caveModesAreOptionalCaseInsensitiveAndRoundTrip() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "enabled=true\n");
        assertEquals(dev.ricky12awesome.lodgen.generation.CaveMode.GENERATE, LodgenConfig.read(file).caveMode());
        for (var mode : dev.ricky12awesome.lodgen.generation.CaveMode.values()) {
            Files.writeString(file, "caveMode='" + mode.name() + "'\n");
            var config = LodgenConfig.read(file);
            assertEquals(mode, config.caveMode());
            LodgenConfig.write(file, config);
            assertEquals(config, LodgenConfig.read(file));
        }
        assertEquals(dev.ricky12awesome.lodgen.generation.CaveMode.GENERATE, dev.ricky12awesome.lodgen.generation.CaveMode.EMPTY.next());
        for (String value : new String[]{"caveMode='invalid'", "caveMode=true"}) {
            Files.writeString(file, value);
            assertFalse(LodgenConfig.load(file).enabled());
            assertEquals(value, Files.readString(file));
        }
    }

    @Test void parsesRealTomlAndPreservesUnrelatedTables() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "enabled = true # Inline comment\ncpuLoad = 0x3\n[notes]\nvalue = 'keep me'\n");
        assertEquals(new LodgenConfig(true, 3, 0, false, 1000, dev.ricky12awesome.lodgen.generation.GenerationCenter.ORIGIN, 0, 0, 0), LodgenConfig.read(file));
        LodgenConfig.write(file, LodgenConfig.DEFAULTS);
        assertTrue(Files.readString(file).contains("keep me"));
    }

    @Test void invalidTypesAndBoundsDisableWithoutRewritingTheBadFile() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        for (String text : new String[]{"enabled = 'false'", "showChunksPerSecond = 1", "generationCenter = 'invalid'", "generationCenter = true", "savedChunkRadius = -1", "centerX = 30000001", "chunksPerSecondUpdateIntervalMs = 0", "chunksPerSecondUpdateIntervalMs = 60001", "chunksPerSecondUpdateIntervalMs = 1.5", "cpuLoad = 0", "cpuLoad = 6", "cpuLoad = 1.5", "enabled = invalid", "generationDistance = -1", "generationDistance = 1.5", "generationDistance = 2147483648"}) {
            Files.writeString(file, text);
            assertFalse(LodgenConfig.load(file).enabled(), text);
            assertEquals(text, Files.readString(file));
        }
    }

    @Test void legacyPlayerCenterUsesOriginWithoutDiscardingOtherSettings() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "generationCenter = 'CURRENT'\ncenterX = 123\ncenterZ = -456\ngenerationDistance = 64\n");
        var config = LodgenConfig.read(file);
        assertEquals(dev.ricky12awesome.lodgen.generation.GenerationCenter.ORIGIN, config.generationCenter());
        assertEquals(123, config.centerX());
        assertEquals(-456, config.centerZ());
        assertEquals(64, config.generationDistance());
        LodgenConfig.write(file, config);
        assertEquals(config, LodgenConfig.read(file));
        assertTrue(Files.readString(file).contains("generationCenter = \"origin\""));
    }
}
