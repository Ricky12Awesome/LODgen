package dev.lodgen.minecraft;

import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import net.minecraft.world.level.chunk.ChunkAccess;

/** Converts generated native chunks to caller-owned DH data sources. */
public final class DhChunkConversion {
    private final IServerLevelWrapper level;
    private final Object nativeLevel;
    private final IWrapperFactory factory;

    public DhChunkConversion(IServerLevelWrapper level, Object nativeLevel) {
        this.level = level;
        this.nativeLevel = nativeLevel;
        this.factory = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
    }

    /** The returned source belongs to the caller and should normally be closed with try-with-resources. */
    public FullDataSourceV2 create(ChunkAccess chunk, Object sourceLevel) {
        var wrapped = factory.createChunkWrapper(new Object[]{chunk, nativeLevel});
        wrapped.createDhHeightMaps();
        var source = FullDataSourceV2.createFromChunk(level, new LitChunkWrapper(wrapped, chunk, sourceLevel));
        if (source == null) throw new IllegalStateException("DH rejected generated chunk at " + chunk.getPos());
        return source;
    }
}
