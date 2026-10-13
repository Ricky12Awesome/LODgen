package dev.ricky12awesome.lodgen;

import com.electronwill.nightconfig.core.CommentedConfig;
import dev.ricky12awesome.lodgen.config.ConfigOption;
import dev.ricky12awesome.lodgen.config.ConfigSchema;
import dev.ricky12awesome.lodgen.generation.CaveMode;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.io.TomlFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Immutable snapshots let generation workers observe complete live changes. */
public record LodgenConfig(
        @ConfigOption(defaultValue = "true", order = 0, path = "generation.autoStart", fileOrder = 3, translationName = "auto_start",
                comment = "Automatically starts a task when joining a new singleplayer world\n\nIf distant horizons generator plan is disabled, this setting is ignored")
        boolean enabled,
        @ConfigOption(defaultValue = "true", order = 1, path = "generation.autoResume", fileOrder = 4, translationName = "auto_resume",
                comment = "Automatically resumes task when joining a singleplayer world or on server startup")
        boolean autoResume,
        @ConfigOption(defaultValue = "3", order = 8, path = "threading.cpuLoad", fileOrder = 2, min = 1, max = 5, cycle = true,
                comment = "CPU Load\n\n1 - Minimum\n2 - Low\n3 - Medium\n4 - High\n5 - Maximum\n\nMore load = faster generation but more lag")
        int cpuLoad,
        @ConfigOption(defaultValue = "0", order = 5, path = "generation.radius", fileOrder = 6, min = 0, max = GenerationArea.MAX_RADIUS, translationName = "lod_radius",
                comment = "These chunks are not saved and are only used for making LODs\n\n0 = based on DH or Voxy")
        int generationDistance,
        @ConfigOption(defaultValue = "true", order = 9, path = "client.showCps.enabled", fileOrder = 0, translationName = "show_throughput",
                comment = "Show chunks per second in action bar (to OPs only)")
        boolean showChunksPerSecond,
        @ConfigOption(defaultValue = "250", order = 10, path = "client.showCps.interval", fileOrder = 1, min = 1, max = 60000, translationName = "overlay_update_interval",
                comment = "Update interval in milliseconds (1-60000)")
        int chunksPerSecondUpdateIntervalMs,
        @ConfigOption(defaultValue = "origin", order = 2, path = "generation.center", fileOrder = 8, translationName = "center_mode",
                comment = "Center\n\norigin - world origin\n<x> <z> - custom x/z location like \"0 0\" or \"512 0\"")
        GenerationCenter generationCenter,
        @ConfigOption(defaultValue = "0", order = 3, persisted = false, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "")
        int centerX,
        @ConfigOption(defaultValue = "0", order = 4, persisted = false, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "")
        int centerZ,
        @ConfigOption(defaultValue = "0", order = 6, path = "generation.saveRadius", fileOrder = 7, min = 0, max = GenerationArea.MAX_RADIUS, translationName = "saved_radius",
                comment = "Save chunks to avoid duplicate work\n\n(basically a chunk pregenerator)\n\n0 = save no chunks")
        int savedChunkRadius,
        @ConfigOption(defaultValue = "empty", order = 7, path = "generation.caveGen", fileOrder = 5,
                comment = "Skips cave generation entirely\n\ngenerate - full chunks\nfill - stone/deepslate\nempty - air\n\nFill/Empty may cause incorrect surfaces\n\nIgnored for saved chunks")
        CaveMode caveMode) {
    public static final Logger LOGGER = LoggerFactory.getLogger("LODgen");
    public static final ConfigSchema<LodgenConfig> SCHEMA = new ConfigSchema<>(LodgenConfig.class, "lodgen.config.option.");
    public static final LodgenConfig DEFAULTS = SCHEMA.defaults();
    public static final Path FILE = Path.of("config", "lodgen.toml");
    private static final int FILE_VERSION = 1;
    private static final CopyOnWriteArrayList<Consumer<LodgenConfig>> LISTENERS = new CopyOnWriteArrayList<>();
    public static volatile LodgenConfig INSTANCE = load(FILE);

    public LodgenConfig(boolean enabled, int cpuLoad, int generationDistance, boolean showChunksPerSecond,
                        int chunksPerSecondUpdateIntervalMs, GenerationCenter generationCenter,
                        int centerX, int centerZ, int savedChunkRadius) {
        this(enabled, cpuLoad, generationDistance, showChunksPerSecond, chunksPerSecondUpdateIntervalMs,
                generationCenter, centerX, centerZ, savedChunkRadius, DEFAULTS.caveMode());
    }

    public LodgenConfig(boolean enabled, int cpuLoad, int generationDistance, boolean showChunksPerSecond,
                        int chunksPerSecondUpdateIntervalMs, GenerationCenter generationCenter,
                        int centerX, int centerZ, int savedChunkRadius, CaveMode caveMode) {
        this(enabled, DEFAULTS.autoResume(), cpuLoad, generationDistance, showChunksPerSecond, chunksPerSecondUpdateIntervalMs,
                generationCenter, centerX, centerZ, savedChunkRadius, caveMode);
    }

    public LodgenConfig {
        SCHEMA.validateValues(enabled, autoResume, cpuLoad, generationDistance, showChunksPerSecond, chunksPerSecondUpdateIntervalMs,
                generationCenter, centerX, centerZ, savedChunkRadius, caveMode);
    }

    /** Zero preserves dedicated-server request ranges when no override is set. */
    public int generationRadius(int dhRadius, boolean integratedServer) {
        return generationDistance > 0 ? generationDistance : integratedServer ? dhRadius : 0;
    }

    static LodgenConfig load(Path file) {
        try {
            if (Files.exists(file)) {
                var values = TomlFiles.read(file);
                var settings = read(values);
                if (!values.contains("_version") || values.<Number>get("_version").intValue() < FILE_VERSION)
                    write(file, settings);
                return settings;
            }
            write(file, DEFAULTS);
            return DEFAULTS;
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Cannot read LODgen configuration; disabling LODgen generation", error);
            return SCHEMA.with(DEFAULTS, "enabled", false);
        }
    }

    static LodgenConfig read(Path file) throws IOException {
        return read(TomlFiles.read(file));
    }

    private static LodgenConfig read(CommentedConfig values) {
        Object version = values.get("_version");
        if (version != null && (!(version instanceof Integer || version instanceof Long)
                || ((Number) version).longValue() < 0 || ((Number) version).longValue() > FILE_VERSION))
            throw new IllegalArgumentException("Unsupported LODgen config version: " + version);
        for (String table : List.of("client", "client.showCps", "threading", "generation")) {
            if (values.contains(table) && !(values.get(table) instanceof CommentedConfig))
                throw new IllegalArgumentException(table + " must be a TOML table");
        }

        // Translate unversioned flat keys while preferring explicitly supplied new keys.
        for (var option : SCHEMA.options()) {
            if (!option.key().equals("generationCenter") && !values.contains(option.path()) && values.contains(option.key()))
                values.set(option.path(), values.get(option.key()));
        }
        if (values.contains("generation.center")) {
            Object center = values.get("generation.center");
            if (!(center instanceof String text)) throw new IllegalArgumentException("generation.center must be a TOML string");
            values.set("centerX", 0);
            values.set("centerZ", 0);
            if (text.strip().equalsIgnoreCase("origin")) values.set("generation.center", "origin");
            else {
                var coordinates = text.strip().split("\\s+");
                if (coordinates.length != 2) throw new IllegalArgumentException("generation.center must be origin or an x/z coordinate pair");
                values.set("centerX", Integer.parseInt(coordinates[0]));
                values.set("centerZ", Integer.parseInt(coordinates[1]));
                values.set("generation.center", "custom");
            }
        } else if (values.contains("generationCenter")) {
            Object center = values.get("generationCenter");
            values.set("generation.center", center instanceof String text && text.equalsIgnoreCase("current") ? "origin" : center);
        }
        return SCHEMA.read(values);
    }

    public static synchronized void apply(LodgenConfig settings) throws IOException {
        write(FILE, settings);
        INSTANCE = settings;
        for (var listener : LISTENERS) {
            try { listener.accept(settings); }
            catch (RuntimeException failure) { LOGGER.error("Cannot apply a live generation setting", failure); }
        }
    }

    public static void listen(Consumer<LodgenConfig> listener) {
        LISTENERS.add(listener);
        listener.accept(INSTANCE);
    }
    public static void stopListening(Consumer<LodgenConfig> listener) { LISTENERS.remove(listener); }

    static void write(Path file, LodgenConfig settings) throws IOException {
        CommentedConfig previous = TomlFiles.newConfig();
        if (Files.exists(file)) {
            try { previous = TomlFiles.read(file); }
            catch (RuntimeException invalidOldFile) { LOGGER.warn("Replacing invalid LODgen TOML with the supplied settings"); }
        }
        var values = TomlFiles.newConfig();
        values.set("_version", FILE_VERSION);
        values.setComment("_version", " tracks different config versions\n only goes up if a config has breaking changes");
        SCHEMA.write(values, settings);
        values.set("generation.center", settings.generationCenter() == GenerationCenter.CUSTOM
                ? settings.centerX() + " " + settings.centerZ() : "origin");
        for (var option : SCHEMA.options()) previous.remove(option.key());
        previous.remove("pipelineBatches"); previous.remove("queuedBatches"); previous.remove("spatialBatching");
        preserveUnknown(values, previous);
        TomlFiles.write(file, values);
    }

    /** Append unrelated settings without disturbing the documented option order. */
    private static void preserveUnknown(CommentedConfig values, CommentedConfig previous) {
        for (var entry : previous.entrySet()) {
            var path = List.of(entry.getKey());
            Object current = values.get(path);
            if (current instanceof CommentedConfig table && entry.getValue() instanceof CommentedConfig oldTable)
                preserveUnknown(table, oldTable);
            else if (!values.contains(path)) values.set(path, entry.getValue());
            if (values.getComment(path) == null && entry.getComment() != null)
                values.setComment(path, entry.getComment());
        }
    }
}
