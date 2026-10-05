package dev.ricky12awesome.lodgen.minecraft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.function.Function;

/** One reader and target cursor per ore placement, rather than per voxel.
 * Section acquisition, live reads and release remain BulkSectionAccess's own.
 */
public final class OreBlockAccess extends BulkSectionAccess implements Function<BlockPos, BlockState> {
    private final Targets targets = new Targets();

    public OreBlockAccess(LevelAccessor level) { super(level); }
    @Override public BlockState apply(BlockPos position) { return getBlockState(position); }

    public Iterator<?> targets(List<?> list) {
        // Preserve custom/mutable list iterators and their fail-fast behavior.
        if (!(list instanceof RandomAccess) || !list.getClass().getName().startsWith("java.util.ImmutableCollections$List")) {
            return list.iterator();
        }
        targets.list = list;
        targets.index = 0;
        return targets;
    }

    private static final class Targets implements Iterator<Object> {
        List<?> list;
        int index;
        @Override public boolean hasNext() { return index < list.size(); }
        @Override public Object next() {
            if (!hasNext()) throw new NoSuchElementException();
            return list.get(index++);
        }
    }
}
