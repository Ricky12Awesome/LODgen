package dev.ricky12awesome.lodgen.minecraft;

import dev.ricky12awesome.lodgen.generation.SavePolicy;
import net.minecraft.world.level.ChunkPos;

import java.nio.file.Files;
import java.nio.file.Path;

/** Shared persistence decisions used by vanilla and optional storage adapters. */
public final class ChunkPersistence {
    private ChunkPersistence() {}

    public static boolean suppress(SavePolicy policy, ChunkPos pos) {
        return policy != null && policy.suppress(PersistenceRegistry.x(pos), PersistenceRegistry.z(pos));
    }

    public static boolean suppress(SavePolicy policy, int x, int z) {
        return policy != null && policy.suppress(x, z);
    }

    /** Region coordinates use arithmetic shifts, including for negative chunks. */
    public static boolean regionExists(Path folder, int chunkX, int chunkZ) {
        return Files.exists(folder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca"));
    }
}
