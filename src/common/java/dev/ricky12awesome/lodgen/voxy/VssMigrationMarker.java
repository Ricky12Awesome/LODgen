package dev.ricky12awesome.lodgen.voxy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;

/** Only a cutover receipt; column data remains entirely in the native VSS store. */
public final class VssMigrationMarker {
    private VssMigrationMarker() {}
    public record Source(String path, long size, long modified, long walSize, long walModified) {}
    public static Source source(Path source) throws IOException {
        Path canonical = source.toRealPath();
        Path wal = source.resolveSibling(source.getFileName() + "-wal");
        // SQLite read-only WAL connections may create an empty WAL. It carries
        // no source transactions and is equivalent to no WAL at all.
        boolean hasWal = Files.isRegularFile(wal) && Files.size(wal) > 0;
        return new Source(canonical.toString(), Files.size(source), Files.getLastModifiedTime(source).toMillis(),
                hasWal ? Files.size(wal) : -1, hasWal ? Files.getLastModifiedTime(wal).toMillis() : -1);
    }
    public static boolean completed(Path marker, Path source) {
        try {
            var properties = new Properties();
            try (var stream = Files.newInputStream(marker)) { properties.load(stream); }
            Source current = source(source);
            return "1".equals(properties.getProperty("version"))
                    && !properties.getProperty("identity", "").isEmpty()
                    && current.path().equals(properties.getProperty("source"))
                    && current.size() == Long.parseLong(properties.getProperty("size"))
                    && current.modified() == Long.parseLong(properties.getProperty("modified"))
                    && current.walSize() == Long.parseLong(properties.getProperty("walSize"))
                    && current.walModified() == Long.parseLong(properties.getProperty("walModified"));
        } catch (IOException | RuntimeException incomplete) { return false; }
    }
    public static void complete(Path marker, Path source, Source expected, String identity, int rows) throws IOException {
        if (!expected.equals(source(source))) throw new IOException("Legacy VSS source changed during migration");
        if (identity.isEmpty()) throw new IOException("Missing legacy VSS migration identity");
        var properties = new Properties();
        properties.setProperty("version", "1"); properties.setProperty("source", expected.path());
        properties.setProperty("size", Long.toString(expected.size()));
        properties.setProperty("modified", Long.toString(expected.modified()));
        properties.setProperty("walSize", Long.toString(expected.walSize()));
        properties.setProperty("walModified", Long.toString(expected.walModified()));
        properties.setProperty("identity", identity); properties.setProperty("rows", Integer.toString(rows));
        var temporary = Files.createTempFile(marker.getParent(), "vss-native-migration-", ".tmp");
        try {
            try (var stream = Files.newOutputStream(temporary)) { properties.store(stream, "Completed native VSS cache cutover"); }
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
