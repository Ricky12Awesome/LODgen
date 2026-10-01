package dev.dhc2me;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record DhC2meConfig(boolean enabled, int concurrentBatches, int queuedBatches, boolean spatialBatching) {
    public static final Logger LOGGER = LoggerFactory.getLogger("DH-C2ME");
    public static final DhC2meConfig INSTANCE = load(Path.of("config", "dhc2me.properties"));

    static DhC2meConfig load(Path path) {
        Properties properties = new Properties();
        try {
            if (Files.exists(path)) {
                try (var reader = Files.newBufferedReader(path)) { properties.load(reader); }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, """
                        # Restart Minecraft after changing these settings.
                        # Uses normal asynchronous chunk loading for DH FEATURES.
                        enabled=true
                        # Per-dimension pipeline bound; 32 batches = 512 target chunks at DH detail 6.
                        pipelineBatches=32
                        queuedBatches=64
                        # Group nearby requests within DH's distance priority bands.
                        spatialBatching=true
                        """);
            }
            String enabled = properties.getProperty("enabled", "true");
            if (!enabled.equals("true") && !enabled.equals("false")) {
                throw new IllegalArgumentException("enabled must be true or false");
            }
            String spatial = properties.getProperty("spatialBatching", "true");
            if (!spatial.equals("true") && !spatial.equals("false")) {
                throw new IllegalArgumentException("spatialBatching must be true or false");
            }
            return new DhC2meConfig(Boolean.parseBoolean(enabled),
                    bounded(properties, "pipelineBatches", 32, 1, 64),
                    bounded(properties, "queuedBatches", 64, 0, 1024),
                    Boolean.parseBoolean(spatial));
        } catch (IOException | IllegalArgumentException error) {
            LOGGER.error("Cannot read DH-C2ME configuration; keeping DH's default generator", error);
            return new DhC2meConfig(false, 1, 64, false);
        }
    }

    private static int bounded(Properties properties, String key, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
}
