package dev.lodgen.generation;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** Written only after Voxy closes/flushed its engine, never ahead of LOD data. */
public final class TileCoverage {
    private static final int MAGIC = 0x4c4f4431;
    public static Set<Long> read(Path file) throws IOException {
        var tiles = new HashSet<Long>();
        if (!Files.exists(file)) return tiles;
        long size = Files.size(file);
        if (size < 4 || (size - 4) % 8 != 0) throw new IOException("Incomplete LODgen tile coverage");
        try (var input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readInt() != MAGIC) throw new IOException("Unknown LODgen tile coverage format");
            for (long i = 4; i < size; i += 8) tiles.add(input.readLong());
        }
        return tiles;
    }
    public static void write(Path file, Set<Long> tiles) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "lodgen-", ".tmp");
        try {
            try (var output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC);
                for (long key : tiles) output.writeLong(key);
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
