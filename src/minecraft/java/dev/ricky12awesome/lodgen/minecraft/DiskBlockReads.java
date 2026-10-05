package dev.ricky12awesome.lodgen.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Reuse native chunk lookups while a disk scans its columns. Block states are
 * always read live, and nested features clear the enclosing chunk references.
 */
public final class DiskBlockReads {
    private static final ThreadLocal<DiskBlockReads> LOCAL = ThreadLocal.withInitial(DiskBlockReads::new);
    private final ChunkAccess[] chunks = new ChunkAccess[16];
    private final int[] chunkXs = new int[16], chunkZs = new int[16];
    private WorldGenLevel level;
    final DiskPredicatePlan.Scratch scratch = new DiskPredicatePlan.Scratch();

    public static Scope open(WorldGenLevel level, boolean eligible) {
        DiskBlockReads cache = LOCAL.get();
        WorldGenLevel previous = cache.level;
        cache.clear();
        cache.scratch.resetMemo();
        cache.level = eligible && level instanceof WorldGenRegion ? level : null;
        return new Scope(cache, previous);
    }

    static DiskBlockReads local() { return LOCAL.get(); }
    boolean matchesLevel(WorldGenLevel level) { return this.level == level; }

    public BlockState get(BlockPos pos) {
        var chunk = chunk(pos);
        var markers = CaveMarkers.at(chunk, pos.getY());
        return markers == null ? chunk.getBlockState(pos) : markers.get(pos);
    }

    public ChunkAccess chunk(BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4, chunkSlot = (cx * 31 ^ cz) & 15;
        ChunkAccess chunk = chunks[chunkSlot];
        if (chunk == null || chunkXs[chunkSlot] != cx || chunkZs[chunkSlot] != cz) {
            // Fetch through the region once, retaining its dependency/bounds
            // checks. The chunk remains owned by this generation task.
            chunk = level.getChunk(cx, cz);
            chunks[chunkSlot] = chunk; chunkXs[chunkSlot] = cx; chunkZs[chunkSlot] = cz;
        }
        return chunk;
    }

    private void clear() {
        java.util.Arrays.fill(chunks, null);
    }

    public record Scope(DiskBlockReads cache, WorldGenLevel previous) implements AutoCloseable {
        @Override public void close() { cache.clear(); cache.level = previous; cache.scratch.resetMemo(); }
    }
}
