package dev.ricky12awesome.lodgen.io;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared TOML parsing and atomic replacement for settings and world checkpoints. */
public final class TomlFiles {
    private TomlFiles() {}

    public static CommentedConfig read(Path path) throws IOException {
        try (var reader = Files.newBufferedReader(path)) { return new TomlParser().parse(reader); }
    }

    public static void write(Path path, UnmodifiableConfig values) throws IOException {
        Path destination = path.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "lodgen-", ".toml.tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { new TomlWriter().write(values, writer); }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
