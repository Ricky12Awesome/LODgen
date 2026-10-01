package dev.lodgen.generation;

/** Keep DH's distance/detail priority in bands, then traverse nearby 4-chunk
 * sections together. Immediate unloading otherwise regenerates the same halo
 * repeatedly when DH hops around a growing frontier without saving chunks.
 */
public final class SpatialGenerationOrder {
    private SpatialGenerationOrder() { }

    public static long priority(int distance, int blockX, int blockZ) {
        int x = blockX >> 6, z = blockZ >> 6;
        long locality = spread(x + 0x80000) | (spread(z + 0x80000) << 1);
        // DH divides distance by section detail; a band is 24 chunks at detail 6.
        return ((long) (distance >>> 6) << 40) | locality;
    }

    private static long spread(int coordinate) {
        // Minecraft's +/-30 million block border fits in 20 biased signed bits.
        long value = coordinate & 0xfffffL;
        value = (value | value << 16) & 0x0000ffff0000ffffL;
        value = (value | value << 8) & 0x00ff00ff00ff00ffL;
        value = (value | value << 4) & 0x0f0f0f0f0f0f0f0fL;
        value = (value | value << 2) & 0x3333333333333333L;
        return (value | value << 1) & 0x5555555555555555L;
    }

    public record Ranked<T>(T value, long priority) { }
}
