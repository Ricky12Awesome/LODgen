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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Immutable snapshots let generation workers observe complete live changes. */
public record LodgenConfig(
        @ConfigOption(defaultValue = "true", order = 0, translationName = "auto_start",
                comment = "Automatically starts a task when joining a new singleplayer world\n\n If distant horizons generator plan is disabled, this setting is ignored")
        boolean enabled,
        @ConfigOption(defaultValue = "true", order = 1, translationName = "auto_resume",
                comment = "Automatically resumes task when joining a singleplayer world or on server startup")
        boolean autoResume,
        @ConfigOption(defaultValue = "3", order = 8, min = 1, max = 5, cycle = true,
                comment = "CPU Load\n 1 - Minimum\n 2 - Low\n 3 - Medium\n 4 - High\n 5 - Maximum\n\n More load = faster generation but more lag")
        int cpuLoad,
        @ConfigOption(defaultValue = "0", order = 5, min = 0, max = GenerationArea.MAX_RADIUS, translationName = "lod_radius",
                comment = "These chunks are not saved and are only used for making LODs\n\n 0 = based on DH or Voxy")
        int generationDistance,
        @ConfigOption(defaultValue = "false", order = 9, translationName = "show_throughput",
                comment = "Show chunks per second in action bar (to OPs only)")
        boolean showChunksPerSecond,
        @ConfigOption(defaultValue = "1000", order = 10, min = 1, max = 60000, translationName = "overlay_update_interval",
                comment = "Update interval in milliseconds (1-60000)")
        int chunksPerSecondUpdateIntervalMs,
        @ConfigOption(defaultValue = "origin", order = 2, translationName = "center_mode",
                comment = "Center\n\n origin - world origin\n custom - use x/z")
        GenerationCenter generationCenter,
        @ConfigOption(defaultValue = "0", order = 3, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "")
        int centerX,
        @ConfigOption(defaultValue = "0", order = 4, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "")
        int centerZ,
        @ConfigOption(defaultValue = "0", order = 6, min = 0, max = GenerationArea.MAX_RADIUS, translationName = "saved_radius",
                comment = "Save chunks to avoid duplicate work\n\n (basically a chunk pregenerator)\n\n 0 = save no chunks")
        int savedChunkRadius,
        @ConfigOption(defaultValue = "generate", order = 7,
                comment = "Skips cave generation entirely\n\n generate - full chunks\n fill - stone/deepslate\n empty - air\n\n Fill/Empty may cause incorrect surfaces\n\n Ignored for saved chunks")
        CaveMode caveMode) {
    public static final Logger LOGGER = LoggerFactory.getLogger("LODgen");
    public static final ConfigSchema<LodgenConfig> SCHEMA = new ConfigSchema<>(LodgenConfig.class, "lodgen.config.option.");
    public static final LodgenConfig DEFAULTS = SCHEMA.defaults();
    public static final Path FILE = Path.of("config", "lodgen.toml");
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
            if (Files.exists(file)) return read(file);
            write(file, DEFAULTS);
            return DEFAULTS;
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Cannot read LODgen configuration; disabling LODgen generation", error);
            return SCHEMA.with(DEFAULTS, "enabled", false);
        }
    }

    static LodgenConfig read(Path file) throws IOException {
        var values = TomlFiles.read(file);
        if (values.get("generationCenter") instanceof String center && center.equalsIgnoreCase("current"))
            values.set("generationCenter", "origin");
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
        CommentedConfig values = CommentedConfig.inMemory();
        if (Files.exists(file)) {
            try { values = TomlFiles.read(file); }
            catch (RuntimeException invalidOldFile) { LOGGER.warn("Replacing invalid LODgen TOML with the supplied settings"); }
        }
        SCHEMA.write(values, settings);
        values.remove("pipelineBatches"); values.remove("queuedBatches"); values.remove("spatialBatching");
        TomlFiles.write(file, values);
    }
}
