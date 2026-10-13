package dev.ricky12awesome.lodgen.io;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;

/** Shared TOML parsing and atomic replacement for settings and world checkpoints. */
public final class TomlFiles {
    private TomlFiles() {}

    /** Keep insertion order for both keys and nested tables. */
    public static CommentedConfig newConfig() { return TomlFormat.newConfig(LinkedHashMap::new); }

    public static CommentedConfig read(Path path) throws IOException {
        var values = newConfig();
        try (var reader = Files.newBufferedReader(path)) { new TomlParser().parse(reader, values, ParsingMode.REPLACE); }
        return values;
    }

    public static void write(Path path, UnmodifiableConfig values) throws IOException {
        Path destination = path.toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "lodgen-", ".toml.tmp");
        try {
            var toml = new TomlWriter();
            toml.setIndent("");
            try (var writer = Files.newBufferedWriter(temporary)) { toml.write(values, writer); }
            try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
