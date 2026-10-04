package dev.lodgen.io;

import com.electronwill.nightconfig.core.CommentedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TomlFilesTest {
    @TempDir Path directory;

    @Test
    void failedSerializationKeepsDestinationAndRemovesTemporaryFile() throws Exception {
        Path destination = directory.resolve("settings.toml");
        Files.writeString(destination, "existing = true\n");
        var invalid = CommentedConfig.inMemory();
        invalid.set("unsupported", new Object());

        assertThrows(RuntimeException.class, () -> TomlFiles.write(destination, invalid));

        assertEquals("existing = true\n", Files.readString(destination));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "Failed serialization must leave no temporary file");
        }
    }

    @Test
    void writesRelativePathAndCreatesNestedParentDirectories() throws Exception {
        Path absoluteDestination = directory.resolve("nested/settings.toml");
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path relativeDestination = workingDirectory.relativize(absoluteDestination.toAbsolutePath());
        var values = CommentedConfig.inMemory();
        values.set("enabled", true);
        values.set("radius", 17);

        TomlFiles.write(relativeDestination, values);

        var parsed = TomlFiles.read(absoluteDestination);
        assertEquals(Boolean.TRUE, parsed.<Boolean>get("enabled"));
        assertEquals(17, parsed.<Number>get("radius").intValue());
    }
}
