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
                comment = "Start a generation task on world load using the configured area. Pause, continue or stop it with /lodgen commands. DH’s Disabled plan blocks autostart. DH chunk phases still use LODgen when this is off; Surface Only adds chunks only when on. Explicit starts run independently.")
        boolean enabled,
        @ConfigOption(defaultValue = "3", order = 7, min = 1, max = 5, cycle = true,
                comment = "CPU utilization targets: 1 minimum (1%, one native batch and conversion worker), 2 low (25%), 3 medium (50%), 4 high (75%), 5 maximum (unthrottled). Native admission accounts for Minecraft/C2ME CPU work. Overrides DH’s thread count and runtime ratio through its API when installed. Changes apply live; active native work drains first.")
        int cpuLoad,
        @ConfigOption(defaultValue = "0", order = 4, min = 0, max = GenerationArea.MAX_RADIUS, translationName = "lod_radius",
                comment = "Radius in chunks for chunk-based LOD generation. 0 uses DH’s or Voxy’s distance. Positive values override it. Changes apply immediately; running work finishes.")
        int generationDistance,
        @ConfigOption(defaultValue = "false", order = 8, translationName = "show_throughput",
                comment = "Show completed LOD chunks per second, generation radius, estimated time remaining and status above the hotbar. Hidden at zero throughput or when DH uses its own overlay.")
        boolean showChunksPerSecond,
        @ConfigOption(defaultValue = "1000", order = 9, min = 1, max = 60000, translationName = "overlay_update_interval",
                comment = "1–60000 milliseconds between action-bar updates. Default: 1000 (one second). The rate and ETA use the last five seconds of throughput. Changes apply immediately.")
        int chunksPerSecondUpdateIntervalMs,
        @ConfigOption(defaultValue = "origin", order = 1, translationName = "center_mode",
                comment = "Origin uses the world’s spawn; custom uses a fixed horizontal X/Z center in blocks. Legacy current values use origin. Commands choose their own fixed center.")
        GenerationCenter generationCenter,
        @ConfigOption(defaultValue = "0", order = 2, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "Custom horizontal X coordinate in blocks. Used only with Custom X/Z.")
        int centerX,
        @ConfigOption(defaultValue = "0", order = 3, min = -GenerationArea.WORLD_EDGE_BLOCKS, max = GenerationArea.WORLD_EDGE_BLOCKS,
                enabledWhen = "generationCenter", enabledValue = "custom", comment = "Custom horizontal Z coordinate in blocks. Used only with Custom X/Z.")
        int centerZ,
        @ConfigOption(defaultValue = "0", order = 5, min = 0, max = GenerationArea.MAX_RADIUS, translationName = "saved_radius",
                comment = "Square radius in chunks to save as ordinary terrain. Default 0 keeps LOD-only terrain transient. Normal player and Chunky chunks always retain their saves.")
        int savedChunkRadius,
        @ConfigOption(defaultValue = "generate", order = 6,
                comment = "Generate: normal caves. Fill: skip caves and underground features, filling with stone/deepslate. Empty: generate air beneath the surface layers. Trees, surface structures and water remain. Saved and player chunks always generate normally. Applies to newly generated LODs.")
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
