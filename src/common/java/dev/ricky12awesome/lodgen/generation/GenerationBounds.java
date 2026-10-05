package dev.ricky12awesome.lodgen.generation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;

/** Square overlap matches DH's render quadtree, including partial edge sections. */
public final class GenerationBounds {
    private GenerationBounds() { }

    /** Resolve cancellation only after removing the exact waiting task. If a
     * dispatcher has taken it, its pooled data must remain owned until it drains.
     */
    public static <T> boolean retain(ConcurrentMap<Long, T> waiting, long pos, T task,
                                    CompletableFuture<?> future, boolean visible) {
        if (visible) return true;
        if (waiting.remove(pos, task)) future.cancel(false);
        return false;
    }

    public static boolean overlaps(int minBlockX, int minBlockZ, int blockWidth,
                                   int targetBlockX, int targetBlockZ, int radiusChunks) {
        long radius = (long) radiusChunks * 16;
        return (long) minBlockX < (long) targetBlockX + radius
                && (long) minBlockX + blockWidth > (long) targetBlockX - radius
                && (long) minBlockZ < (long) targetBlockZ + radius
                && (long) minBlockZ + blockWidth > (long) targetBlockZ - radius;
    }
}
