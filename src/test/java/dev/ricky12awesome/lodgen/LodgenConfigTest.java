package dev.ricky12awesome.lodgen;

import dev.ricky12awesome.lodgen.generation.CaveMode;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.io.TomlFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LodgenConfigTest {
    @TempDir Path directory;

    @Test void generatedDefaultsMatchDocumentedLayoutAndComments() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        assertEquals(LodgenConfig.DEFAULTS, LodgenConfig.load(file));
        try (var expected = getClass().getResourceAsStream("/lodgen-default.toml")) {
            assertNotNull(expected);
            String fixture = new String(expected.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("@THREADS@", Integer.toString(Math.max(1, (Runtime.getRuntime().availableProcessors() + 1) / 2)));
            assertEquals(fixture, Files.readString(file).replace("\r\n", "\n"));
        }
        assertEquals(LodgenConfig.DEFAULTS, LodgenConfig.read(file));
    }

    @Test void createsTomlAndRoundTripsAllSettings() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        assertEquals(LodgenConfig.DEFAULTS, LodgenConfig.load(file));
        int threads = Math.min(5, Runtime.getRuntime().availableProcessors());
        var edited = new LodgenConfig(false, false, threads, false, 64, true, 250, GenerationCenter.CUSTOM, 1234, -5678, 2, CaveMode.EMPTY);
        LodgenConfig.write(file, edited);
        assertEquals(edited, LodgenConfig.read(file));
        assertTrue(Files.readString(file).contains("threads = " + threads));
        assertTrue(Files.readString(file).contains("autoResume = false"));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count(), "No temporary file remains"); }
    }

    @Test void legacyConfigDefaultsAutoResumeAndDraftCanToggleIt() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "enabled=false\ncpuLoad=4\n");
        int expectedThreads = (int) Math.ceil(Runtime.getRuntime().availableProcessors() * .75);
        var config = LodgenConfig.read(file);
        assertTrue(config.autoResume());
        var option = LodgenConfig.SCHEMA.option("autoResume");
        assertEquals(dev.ricky12awesome.lodgen.config.ConfigSchema.Control.TOGGLE, option.control());
        assertEquals("lodgen.config.option.auto_resume.label", option.labelKey());
        assertEquals("lodgen.config.option.auto_resume.tooltip", option.tooltipKey());
        var draft = LodgenConfig.SCHEMA.draft(config);
        draft.cycle(option);
        var edited = draft.snapshot();
        assertFalse(edited.autoResume());
        assertFalse(edited.enabled());
        assertEquals(expectedThreads, edited.threads());
        LodgenConfig.write(file, edited);
        assertEquals(edited, LodgenConfig.read(file));
    }

    @Test void legacyCpuPresetsMapToThreadsAndInvalidPresetsAreIgnored() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        int processors = Runtime.getRuntime().availableProcessors();
        for (int load = 1; load <= 5; load++) {
            Files.writeString(file, "cpuLoad = " + load + "\n");
            int expected = load == 1 ? 1 : (int) Math.ceil(processors * (load - 1) / 4.0);
            assertEquals(expected, LodgenConfig.read(file).threads(), "legacy preset " + load);
        }
        for (String text : List.of("cpuLoad = 0", "cpuLoad = 6", "cpuLoad = 1.5", "threading.cpuLoad = 'high'")) {
            Files.writeString(file, text);
            assertEquals(LodgenConfig.DEFAULTS.threads(), LodgenConfig.read(file).threads(), text);
        }
    }

    @Test void versionOneMissingThreadSettingsAreMigratedAndLegacyThreadingTableIsRemoved() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, """
                _version = 1
                cpuLoad = 2
                [threading]
                cpuLoad = 5
                [notes]
                value = 'keep me'
                """);
        int processors = Runtime.getRuntime().availableProcessors();
        int expectedThreads = processors;
        assertEquals(expectedThreads, LodgenConfig.load(file).threads());
        var migrated = TomlFiles.read(file);
        assertEquals(1, migrated.<Number>get("_version").intValue());
        assertEquals(expectedThreads, migrated.<Number>get("generation.threads").intValue());
        assertTrue(migrated.<Boolean>get("generation.preventLag"));
        assertFalse(migrated.contains("cpuLoad"));
        assertFalse(migrated.contains("threading"));
        assertEquals("keep me", migrated.get("notes.value"));
    }

    @Test void explicitThreadsWinOverLegacyCpuPresetsAndPortableValuesAreClamped() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        int selectedThreads = Math.min(2, Runtime.getRuntime().availableProcessors());
        Files.writeString(file, "cpuLoad = 5\nthreading.cpuLoad = 4\ngeneration.threads = " + selectedThreads + "\n");
        assertEquals(selectedThreads, LodgenConfig.read(file).threads());
        Files.writeString(file, "generation.threads = " + (Runtime.getRuntime().availableProcessors() + 1) + "\n");
        assertEquals(Runtime.getRuntime().availableProcessors(), LodgenConfig.read(file).threads());
    }

    @Test void migrationWritesThreadsWhenPreventLagWasAlreadySupplied() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "_version=1\nthreading.cpuLoad=1\ngeneration.preventLag=false\n");
        var settings = LodgenConfig.load(file);
        assertEquals(1, settings.threads());
        assertFalse(settings.preventLag());
        var values = TomlFiles.read(file);
        assertEquals(1, values.<Number>get("generation.threads").intValue());
        assertFalse(values.contains("threading"));
        assertEquals(1, values.<Number>get("_version").intValue());
    }

    @Test void preventLagDefaultsTrueRoundTripsAndRejectsInvalidTypes() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        assertTrue(LodgenConfig.DEFAULTS.preventLag());
        Files.writeString(file, "generation.preventLag = false\n");
        var edited = LodgenConfig.read(file);
        assertFalse(edited.preventLag());
        LodgenConfig.write(file, edited);
        assertFalse(LodgenConfig.read(file).preventLag());
        for (String text : List.of("generation.preventLag = 1", "generation.preventLag = 'false'")) {
            Files.writeString(file, text);
            assertFalse(LodgenConfig.load(file).enabled(), text);
            assertEquals(text, Files.readString(file));
        }
    }

    @Test void caveModesAreOptionalCaseInsensitiveAndRoundTrip() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, "enabled=true\n");
        assertEquals(CaveMode.EMPTY, LodgenConfig.read(file).caveMode());
        for (var mode : dev.ricky12awesome.lodgen.generation.CaveMode.values()) {
            for (String key : List.of("caveMode", "generation.caveGen")) {
                Files.writeString(file, key + "='" + mode.name() + "'\n");
                var config = LodgenConfig.read(file);
                assertEquals(mode, config.caveMode());
                LodgenConfig.write(file, config);
                assertEquals(config, LodgenConfig.read(file));
            }
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
        assertEquals(LodgenConfig.DEFAULTS, LodgenConfig.read(file));
        LodgenConfig.write(file, LodgenConfig.DEFAULTS);
        assertTrue(Files.readString(file).contains("keep me"));
    }

    @Test void invalidTypesAndBoundsDisableWithoutRewritingTheBadFile() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        for (String text : new String[]{"enabled = 'false'", "autoResume = 'false'", "autoResume = 1", "showChunksPerSecond = 1", "generationCenter = 'invalid'", "generationCenter = true", "savedChunkRadius = -1", "centerX = 30000001", "chunksPerSecondUpdateIntervalMs = 0", "chunksPerSecondUpdateIntervalMs = 60001", "chunksPerSecondUpdateIntervalMs = 1.5", "enabled = invalid", "generationDistance = -1", "generationDistance = 1.5", "generationDistance = 2147483648"}) {
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
        var reloaded = LodgenConfig.read(file);
        assertEquals(GenerationCenter.ORIGIN, reloaded.generationCenter());
        assertEquals(64, reloaded.generationDistance());
        assertTrue(Files.readString(file).contains("center = \"origin\""));
        assertFalse(Files.readString(file).contains("generationCenter"));
    }

    @Test void migratesLegacyConfigOnLoadAndPreservesExistingValuesAndUnknownSettings() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        Files.writeString(file, """
                enabled = false
                autoResume = false
                cpuLoad = 4
                generationDistance = 64
                showChunksPerSecond = false
                chunksPerSecondUpdateIntervalMs = 1000
                generationCenter = "CUSTOM"
                centerX = 1234
                centerZ = -5678
                savedChunkRadius = 2
                caveMode = "generate"
                pipelineBatches = 5
                queuedBatches = 8
                spatialBatching = true
                [notes]
                # user comment
                value = 'keep me'
                "dotted.key" = 7
                [generation.extra]
                value = 'nested note'
                """);
        int migratedThreads = (int) Math.ceil(Runtime.getRuntime().availableProcessors() * .75);
        var expected = new LodgenConfig(false, false, migratedThreads, true, 64, false, 1000, GenerationCenter.CUSTOM, 1234, -5678, 2, CaveMode.GENERATE);
        assertEquals(expected, LodgenConfig.load(file));
        assertEquals(expected, LodgenConfig.read(file));
        var migrated = TomlFiles.read(file);
        assertEquals(1, migrated.<Number>get("_version").intValue());
        assertEquals("1234 -5678", migrated.get("generation.center"));
        assertEquals("keep me", migrated.get("notes.value"));
        assertEquals(" user comment", migrated.getComment("notes.value"));
        assertEquals(7, migrated.<Number>get(List.of("notes", "dotted.key")).intValue());
        assertEquals("nested note", migrated.get("generation.extra.value"));
        for (String oldKey : List.of("enabled", "autoResume", "cpuLoad", "generationDistance", "showChunksPerSecond",
                "chunksPerSecondUpdateIntervalMs", "generationCenter", "centerX", "centerZ", "savedChunkRadius", "caveMode",
                "pipelineBatches", "queuedBatches", "spatialBatching")) assertFalse(migrated.contains(oldKey), oldKey);
    }

    @Test void readsNewSectionsAndPrefersThemOverLegacyKeys() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        int threads = Math.min(7, Runtime.getRuntime().availableProcessors());
        Files.writeString(file, """
                _version = 1
                enabled = true
                cpuLoad = 2
                generationCenter = 'custom'
                centerX = 99
                centerZ = 99
                [generation]
                center = 'origin'
                saveRadius = 32
                radius = 64
                caveGen = 'FILL'
                autoResume = false
                threads = %d
                preventLag = false
                autoStart = false
                [threading]
                cpuLoad = 5
                [client.showCps]
                interval = 100
                enabled = false
                extra = 'keep this'
                """.formatted(threads));
        var expected = new LodgenConfig(false, false, threads, false, 64, false, 100, GenerationCenter.ORIGIN, 0, 0, 32, CaveMode.FILL);
        assertEquals(expected, LodgenConfig.load(file));
        LodgenConfig.write(file, expected);
        assertEquals(expected, LodgenConfig.read(file));
        var values = TomlFiles.read(file);
        assertEquals(List.of("_version", "client", "generation"),
                values.entrySet().stream().map(entry -> entry.getKey()).toList());
        assertEquals(List.of("threads", "preventLag", "autoStart", "autoResume", "caveGen", "radius", "saveRadius", "center"),
                values.<com.electronwill.nightconfig.core.CommentedConfig>get("generation").entrySet().stream().map(entry -> entry.getKey()).toList());
        assertEquals("keep this", values.get("client.showCps.extra"));
        assertFalse(values.contains("enabled"));
    }

    @Test void parsesAndRoundTripsCoordinatePairCentersIncludingZeroAndWorldBounds() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        for (String center : List.of("0 0", "512 0", "-1234 5678", "  +512   -1024  ", "-30000000 30000000")) {
            Files.writeString(file, "[generation]\ncenter = '" + center + "'\n");
            var config = LodgenConfig.read(file);
            var coordinates = center.strip().split("\\s+");
            assertEquals(GenerationCenter.CUSTOM, config.generationCenter());
            assertEquals(Integer.parseInt(coordinates[0]), config.centerX());
            assertEquals(Integer.parseInt(coordinates[1]), config.centerZ());
            LodgenConfig.write(file, config);
            assertEquals(config, LodgenConfig.read(file));
        }
        Files.writeString(file, "[generation]\ncenter = 'ORIGIN'\n");
        assertEquals(LodgenConfig.DEFAULTS, LodgenConfig.read(file));
    }

    @Test void rejectsInvalidNewSettingsAndUnsupportedVersionsWithoutRewritingFile() throws Exception {
        Path file = directory.resolve("lodgen.toml");
        for (String text : List.of("_version = -1", "_version = 2", "_version = 1.0", "_version = '1'", "_version = true",
                "generation.autoStart = 'false'", "generation.autoResume = 1", "generation.caveGen = 'invalid'", "generation.caveGen = true",
                "generation.radius = -1", "generation.radius = 3750001", "generation.radius = 2147483648", "generation.radius = 1.5",
                "generation.saveRadius = -1", "generation.saveRadius = 3750001", "generation.threads = 0", "generation.threads = -1",
                "generation.threads = 2147483648", "generation.threads = 1.5", "generation.threads = '2'", "generation.preventLag = 1",
                "client.showCps.enabled = 1", "client.showCps.interval = 0", "client.showCps.interval = 60001", "client.showCps.interval = 1.5",
                "generation.center = true", "generation.center = ''", "generation.center = 'custom'", "generation.center = 'current'",
                "generation.center = '512'", "generation.center = '1 2 3'", "generation.center = '1.5 0'", "generation.center = 'x z'",
                "generation.center = '30000001 0'", "generation.center = '0 -30000001'", "generation.center = '2147483648 0'",
                "client = false", "client.showCps = false", "threading = false", "generation = false")) {
            Files.writeString(file, text);
            assertFalse(LodgenConfig.load(file).enabled(), text);
            assertEquals(text, Files.readString(file));
        }
    }
}
