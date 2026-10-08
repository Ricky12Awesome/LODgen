package dev.ricky12awesome.lodgen.voxy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Existing saved chunks supersede generated columns, including edits made while LODgen was absent. */
public final class VssRegionFiles {
    private record Header(long expires, long[] stamps) {}
    private final Function<String, Path> directories;
    private final Map<Path, Header> headers = new LinkedHashMap<>(16, .75f, true);

    public VssRegionFiles(Function<String, Path> directories) { this.directories = directories; }

    public synchronized boolean supersedes(String dimension, long packed, long stamp) {
        Path directory = directories.apply(dimension);
        if (directory == null) return true;
        int x = (int) (packed >> 32), z = (int) packed;
        Path path = directory.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        long now = System.nanoTime();
        Header header = headers.get(path);
        if (header == null || now >= header.expires) {
            long[] stamps = new long[1024];
            try {
                if (Files.exists(path)) try (var file = new RandomAccessFile(path.toFile(), "r")) {
                    int[] locations = new int[1024];
                    for (int index = 0; index < 1024; index++) locations[index] = file.readInt();
                    for (int index = 0; index < 1024; index++) {
                        long seconds = Integer.toUnsignedLong(file.readInt());
                        if (locations[index] != 0) stamps[index] = seconds == 0 ? Long.MAX_VALUE : seconds;
                    }
                }
            } catch (IOException error) { java.util.Arrays.fill(stamps, Long.MAX_VALUE); }
            header = new Header(now + 5_000_000_000L, stamps);
            headers.put(path, header);
            if (headers.size() > 4096) headers.remove(headers.keySet().iterator().next());
        }
        // A missing file/slot is expected for LOD-only terrain and must survive restart.
        return header.stamps[(x & 31) + ((z & 31) << 5)] >= stamp;
    }
}
