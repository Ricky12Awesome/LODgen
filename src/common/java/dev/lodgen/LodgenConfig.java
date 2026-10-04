package dev.lodgen;

import com.electronwill.nightconfig.core.CommentedConfig;
import dev.lodgen.config.ConfigOption;
import dev.lodgen.config.ConfigSchema;
import dev.lodgen.generation.CaveMode;
import dev.lodgen.generation.GenerationArea;
import dev.lodgen.generation.GenerationCenter;
import dev.lodgen.io.TomlFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Immutable snapshots let generation workers observe complete live changes. */
public record LodgenConfig(
        @ConfigOption(defaultValue = "true", label = "Automatic generation", order = 0,
                comment = "Start a generation task on world load using the configured area. Pause, continue or stop it with /lodgen commands. DH’s Disabled plan blocks autostart. DH chunk phases still use LODgen when this is off; Surface Only adds chunks only when on. Explicit starts run independently.")
        boolean enabled,
        @ConfigOption(defaultValue = "3", label = "CPU load", order = 7, min = 1, max = 5, cycle = true,
                disabledWithMod = "distanthorizons", disabledValueKey = "lodgen.config.cpuLoad.dh",
                comment = "Voxy utilization: 1 minimal impact, 2 low impact, 3 balanced, 4 aggressive, 5 full power. Uses DH’s CPU Load setting when DH is installed. Concurrency and conversion workers adjust automatically; more RAM allows a larger native work window.")
        int cpuLoad,
        @ConfigOption(defaultValue = "0", label = "Generation distance", order = 4, min = 0, max = GenerationArea.MAX_RADIUS,
                comment = "Radius in chunks for chunk-based LOD generation. 0 uses DH’s or Voxy’s distance. Positive values override it. Changes apply immediately; running work finishes.")
        int generationDistance,
        @ConfigOption(defaultValue = "false", label = "Show chunks per second", order = 8,
                comment = "Show completed LOD chunks per second, generation radius, estimated time remaining and status above the hotbar. Hidden at zero throughput or when DH uses its own overlay.")
        boolean showChunksPerSecond,
        @ConfigOption(defaultValue = "1000", label = "Overlay update interval (ms)", order = 9, min = 1, max = 60000,
                comment = "1–60000 milliseconds between action-bar updates. Default: 1000 (one second). The rate and ETA use the last five seconds of throughput. Changes apply immediately.")
        int chunksPerSecondUpdateIntervalMs,
        @ConfigOption(defaultValue = "current", label = "Generation center", order = 1, valueKey = "lodgen.config.center.",
                comment = "Choose the moving player position, the world’s spawn, or a fixed horizontal X/Z center. Commands choose their own fixed center.")
        GenerationCenter generationCenter,
        @ConfigOption(defaultValue = "0", label = "Center X (blocks)", order = 2, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "Custom horizontal X coordinate in blocks. Used only with Custom X/Z.")
        int centerX,
        @ConfigOption(defaultValue = "0", label = "Center Z (blocks)", order = 3, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "Custom horizontal Z coordinate in blocks. Used only with Custom X/Z.")
        int centerZ,
        @ConfigOption(defaultValue = "0", label = "Saved chunk radius", order = 5, min = 0, max = GenerationArea.MAX_RADIUS,
                comment = "Square radius in chunks to save as ordinary terrain. Default 0 keeps LOD-only terrain transient. Normal player and Chunky chunks always retain their saves.")
        int savedChunkRadius,
        @ConfigOption(defaultValue = "generate", label = "LOD caves", order = 6, valueKey = "lodgen.config.cave.",
                comment = "Generate: normal caves. Fill: skip caves and underground features, filling with stone/deepslate. Empty: generate air beneath the surface layers. Trees, surface structures and water remain. Saved and player chunks always generate normally. Applies to newly generated LODs.")
        CaveMode caveMode) {
    public static final Logger LOGGER = LoggerFactory.getLogger("LODgen");
    public static final ConfigSchema<LodgenConfig> SCHEMA = new ConfigSchema<>(LodgenConfig.class, "lodgen.config.");
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

    public LodgenConfig {
        SCHEMA.validateValues(enabled, cpuLoad, generationDistance, showChunksPerSecond, chunksPerSecondUpdateIntervalMs,
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
        return SCHEMA.read(TomlFiles.read(file));
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
