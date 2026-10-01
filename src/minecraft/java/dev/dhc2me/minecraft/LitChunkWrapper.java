package dev.dhc2me.minecraft;

import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.block.IBlockStateWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IMutableBlockPosWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IBiomeWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.core.SectionPos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;

import java.util.ArrayList;

/** Read lighting already produced by vanilla/ScalableLux instead of repeating
 * DH's CPU lighting bake. Snapshot nibble arrays while our tickets are held.
 */
public final class LitChunkWrapper implements IChunkWrapper {
    private final IChunkWrapper delegate;
    private final DataLayer[] block;
    private final DataLayer[] sky;
    private final int[][] skyDefaults;
    private final int aboveSky;
    private final int minSection;

    public LitChunkWrapper(IChunkWrapper delegate, ChunkAccess chunk, Object nativeLevel) {
        this.delegate = delegate;
        ServerLevel level = (ServerLevel) nativeLevel;
        minSection = Math.floorDiv(delegate.getInclusiveMinBuildHeight(), 16);
        int count = Math.floorDiv(delegate.getHeight() + 15, 16);
        block = new DataLayer[count];
        sky = new DataLayer[count];
        skyDefaults = new int[count][];
        aboveSky = level.dimensionType().hasSkyLight() ? 15 : 0;
        var engine = level.getChunkSource().getLightEngine();
        var skyListener = engine.getLayerListener(LightLayer.SKY);
        var mutable = new BlockPos.MutableBlockPos();
        for (int i = 0; i < count; i++) {
            var pos = SectionPos.of(chunk.getPos(), minSection + i);
            DataLayer b = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(pos);
            DataLayer s = engine.getLayerListener(LightLayer.SKY).getDataLayerData(pos);
            block[i] = b == null ? null : b.copy();
            sky[i] = s == null ? null : s.copy();
            if (s == null) {
                // A missing sky layer inherits the bottom row of a layer above;
                // above the lit data it is full sky. Ask the installed engine so
                // ScalableLux's representation gets the same treatment.
                skyDefaults[i] = new int[256];
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    mutable.set(chunk.getPos().getMinBlockX() + x, (minSection + i) * 16, chunk.getPos().getMinBlockZ() + z);
                    skyDefaults[i][x | z << 4] = skyListener.getLightValue(mutable);
                }
            }
        }
    }

    private LitChunkWrapper(IChunkWrapper delegate, LitChunkWrapper original) {
        this.delegate = delegate;
        block = original.block;
        sky = original.sky;
        skyDefaults = original.skyDefaults;
        aboveSky = original.aboveSky;
        minSection = original.minSection;
    }

    private int light(DataLayer[] layers, int x, int y, int z) {
        int index = Math.floorDiv(y, 16) - minSection;
        return index < 0 || index >= layers.length || layers[index] == null ? 0 : layers[index].get(x & 15, y & 15, z & 15);
    }
    @Override public int getDhBlockLight(int x, int y, int z) { return light(block, x, y, z); }
    @Override public int getDhSkyLight(int x, int y, int z) {
        int index = Math.floorDiv(y, 16) - minSection;
        if (index >= sky.length) return aboveSky;
        if (index < 0) return 0;
        return sky[index] == null ? skyDefaults[index][(x & 15) | (z & 15) << 4] : sky[index].get(x & 15, y & 15, z & 15);
    }
    @Override public boolean isDhBlockLightingCorrect() { return true; }
    @Override public boolean isDhSkyLightCorrect() { return true; }
    @Override public void setIsDhBlockLightCorrect(boolean correct) { }
    @Override public void setIsDhSkyLightCorrect(boolean correct) { }
    @Override public void setDhBlockLight(int x, int y, int z, int value) { throw new UnsupportedOperationException("Lighting snapshot"); }
    @Override public void setDhSkyLight(int x, int y, int z, int value) { throw new UnsupportedOperationException("Lighting snapshot"); }
    @Override public void clearDhBlockLighting() { throw new UnsupportedOperationException("Lighting snapshot"); }
    @Override public void clearDhSkyLighting() { throw new UnsupportedOperationException("Lighting snapshot"); }
    @Override public DhChunkPos getChunkPos() { return delegate.getChunkPos(); }
    @Override public int getInclusiveMinBuildHeight() { return delegate.getInclusiveMinBuildHeight(); }
    @Override public int getExclusiveMaxBuildHeight() { return delegate.getExclusiveMaxBuildHeight(); }
    @Override public int getMinNonEmptyHeight() { return delegate.getMinNonEmptyHeight(); }
    @Override public int getMaxNonEmptyHeight() { return delegate.getMaxNonEmptyHeight(); }
    @Override public void createDhHeightMaps() { delegate.createDhHeightMaps(); }
    @Override public int getSolidHeightMapValue(int x, int z) { return delegate.getSolidHeightMapValue(x, z); }
    @Override public int getLightBlockingHeightMapValue(int x, int z) { return delegate.getLightBlockingHeightMapValue(x, z); }
    @Override public int getMinBlockX() { return delegate.getMinBlockX(); }
    @Override public int getMinBlockZ() { return delegate.getMinBlockZ(); }
    @Override public ArrayList<DhBlockPos> getWorldBlockLightPosList() { return delegate.getWorldBlockLightPosList(); }
    @Override public IBlockStateWrapper getBlockState(int x, int y, int z) { return delegate.getBlockState(x, y, z); }
    @Override public IBlockStateWrapper getBlockState(int x, int y, int z, IMutableBlockPosWrapper pos, IBlockStateWrapper guess) { return delegate.getBlockState(x, y, z, pos, guess); }
    @Override public IMutableBlockPosWrapper getMutableBlockPosWrapper() { return delegate.getMutableBlockPosWrapper(); }
    @Override public IBiomeWrapper getBiome(int x, int y, int z) { return delegate.getBiome(x, y, z); }
    @Override public IChunkWrapper copy() { return new LitChunkWrapper(delegate.copy(), this); }
    @Override public IChunkWrapper copyWithLevel(ILevelWrapper level) { return new LitChunkWrapper(delegate.copyWithLevel(level), this); }
    @Override public String toString() { return "Lit[" + delegate + "]"; }
}
