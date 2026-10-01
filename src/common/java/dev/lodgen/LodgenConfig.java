package dev.lodgen;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Immutable snapshots let generation workers observe complete live changes. */
public record LodgenConfig(boolean enabled, int pipelineBatches, int queuedBatches, boolean spatialBatching, int generationDistance) {
    public static final Logger LOGGER = LoggerFactory.getLogger("LODgen");
    public static final LodgenConfig DEFAULTS = new LodgenConfig(true, 32, 64, true, 0);
    public static final Path FILE = Path.of("config", "lodgen.toml");
    private static final CopyOnWriteArrayList<Consumer<LodgenConfig>> LISTENERS = new CopyOnWriteArrayList<>();
    public static volatile LodgenConfig INSTANCE = load(FILE);

    public LodgenConfig {
        if (generationDistance < 0) throw new IllegalArgumentException("generationDistance must be nonnegative");
        if (pipelineBatches < 1 || pipelineBatches > 64) throw new IllegalArgumentException("pipelineBatches must be 1–64");
        if (queuedBatches < 0 || queuedBatches > 1024) throw new IllegalArgumentException("queuedBatches must be 0–1024");
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
            return new LodgenConfig(false, 32, 64, true, 0);
        }
    }

    static LodgenConfig read(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            var values = new TomlParser().parse(reader);
            return new LodgenConfig(bool(values.get("enabled"), DEFAULTS.enabled(), "enabled"),
                    integer(values.get("pipelineBatches"), DEFAULTS.pipelineBatches(), "pipelineBatches"),
                    integer(values.get("queuedBatches"), DEFAULTS.queuedBatches(), "queuedBatches"),
                    bool(values.get("spatialBatching"), DEFAULTS.spatialBatching(), "spatialBatching"),
                    integer(values.get("generationDistance"), DEFAULTS.generationDistance(), "generationDistance"));
        }
    }

    private static boolean bool(Object value, boolean fallback, String key) {
        if (value == null) return fallback;
        if (value instanceof Boolean result) return result;
        throw new IllegalArgumentException(key + " must be a TOML boolean");
    }

    private static int integer(Object value, int fallback, String key) {
        if (value == null) return fallback;
        if (value instanceof Integer result) return result;
        if (value instanceof Long result) return Math.toIntExact(result);
        throw new IllegalArgumentException(key + " must be a TOML integer");
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
            try (var reader = Files.newBufferedReader(file)) { values = new TomlParser().parse(reader); }
            catch (RuntimeException invalidOldFile) { LOGGER.warn("Replacing invalid LODgen TOML with the supplied settings"); }
        }
        values.set("generationDistance", settings.generationDistance());
        values.set("enabled", settings.enabled());
        values.set("pipelineBatches", settings.pipelineBatches());
        values.set("queuedBatches", settings.queuedBatches());
        values.set("spatialBatching", settings.spatialBatching());
        values.setComment("generationDistance", " Chunk-based LOD generation radius in chunks. 0 follows DH/Voxy; positive values override it.");
        values.setComment("enabled", " Use normal asynchronous chunk generation for DH FEATURES and Voxy. Changes apply in game.");
        values.setComment("pipelineBatches", " Active batches per dimension (1–64). 32 batches = 512 target chunks at DH detail 6.");
        values.setComment("queuedBatches", " Waiting batches (0–1024). Waiting requests do not occupy workers.");
        values.setComment("spatialBatching", " Group nearby requests within DH's distance priority bands.");
        Path destination = file.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "lodgen-", ".toml.tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { new TomlWriter().write(values, writer); }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
