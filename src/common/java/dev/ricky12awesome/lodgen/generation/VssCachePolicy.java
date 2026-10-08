package dev.ricky12awesome.lodgen.generation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/** Freshness metadata for native VSS rows whose Minecraft source was never saved. */
public final class VssCachePolicy {
    private VssCachePolicy() {}

    public static boolean knownDirectory(Path path, LinkOption... options) {
        return Files.isDirectory(path, options) || Files.notExists(path, options);
    }

    public static boolean regionCandidate(Path path, LinkOption... options) {
        return Files.exists(path, options) || Files.notExists(path, options);
    }

    public static FileTime regionMtime(Path path, LinkOption... options) throws IOException {
        try {
            return Files.getLastModifiedTime(path, options);
        } catch (NoSuchFileException absent) {
            return FileTime.fromMillis(0);
        }
    }

    /** Missing files and empty location slots have no saved source timestamp.
     * Other unreadable or truncated headers retain VSS's fail-safe null result.
     */
    public static int[] readHeaderTimestamps(Path path) {
        try (FileChannel channel = FileChannel.open(path)) {
            if (channel.size() == 0) return new int[1024];
            ByteBuffer header = ByteBuffer.allocate(8192);
            int read = 0;
            while (read < header.capacity()) {
                int count = channel.read(header, read);
                if (count < 0) return null;
                read += count;
            }
            header.flip();
            int[] timestamps = new int[1024];
            for (int i = 0; i < timestamps.length; i++) {
                timestamps[i] = header.getInt(i * 4) == 0 ? 0 : header.getInt(4096 + i * 4);
            }
            return timestamps;
        } catch (NoSuchFileException absent) {
            return new int[1024];
        } catch (Exception unreadable) {
            return null;
        }
    }
}
