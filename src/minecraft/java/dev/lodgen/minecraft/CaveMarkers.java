package dev.lodgen.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/** WWOO uses the bottom eight layers as palette metadata for surface placement.
 * Empty keeps those queries and writes in a separate array, never in terrain,
 * lighting or renderer data. This does not sample or generate any cave noise.
 */
public final class CaveMarkers {
    public static final int HEIGHT = 8;
    private final int minY;
    private final BlockState[] states = new BlockState[256 * HEIGHT];
    private final PositionalRandomFactory bedrock;
    private final BlockState defaultBlock;

    public CaveMarkers(int minY, PositionalRandomFactory bedrock, BlockState defaultBlock) {
        this.minY = minY; this.bedrock = bedrock; this.defaultBlock = defaultBlock;
    }
    public static CaveSurface data(ChunkAccess chunk) {
        if (chunk instanceof ImposterProtoChunk imposter) chunk = imposter.getWrapped();
        return (CaveSurface) chunk;
    }
    public static CaveMarkers at(ChunkAccess chunk, int y) {
        if (y < PersistenceRegistry.minY(chunk) || y >= PersistenceRegistry.minY(chunk) + HEIGHT) return null;
        return data(chunk).lodgen$markers();
    }
    private int index(BlockPos position) {
        return (position.getY() - minY) * 256 + (position.getX() & 15) + (position.getZ() & 15) * 16;
    }
    public BlockState get(BlockPos position) {
        int index = index(position);
        var state = states[index];
        if (state == null) {
            int depth = position.getY() - minY;
            state = depth == 0 || depth < 5 && bedrock.at(position.getX(), position.getY(), position.getZ()).nextFloat() < 1.0F - depth / 5.0F
                    ? Blocks.BEDROCK.defaultBlockState() : position.getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : defaultBlock;
            states[index] = state;
        }
        return state;
    }
    public void set(BlockPos position, BlockState state) { states[index(position)] = state; }
}
