package dev.lodgen.generation;

/** Expand compact 32-chunk patches from the selected center. Within each
 * patch, its nearest 4-chunk sections go first, independent of world origin.
 */
public final class SpatialGenerationOrder {
    private SpatialGenerationOrder() { }

    public static long priority(int blockX, int blockZ, int centerX, int centerZ) {
        int x = Math.floorDiv(blockX - centerX + 256, 512), z = Math.floorDiv(blockZ - centerZ + 256, 512);
        long ring = Math.max(Math.abs(x), Math.abs(z));
        long patch = spread(x + 0x20000) | spread(z + 0x20000) << 1;
        int localX = Math.floorDiv(blockX - centerX + 256, 64) & 7, localZ = Math.floorDiv(blockZ - centerZ + 256, 64) & 7;
        int nearestX = x < 0 ? 7 : x > 0 ? 0 : 4, nearestZ = z < 0 ? 7 : z > 0 ? 0 : 4;
        int localRing = Math.max(Math.abs(localX - nearestX), Math.abs(localZ - nearestZ));
        return ring << 46 | patch << 10 | (long) localRing << 6 | spread(localX) | spread(localZ) << 1;
    }

    private static long spread(int coordinate) {
        // Center-relative 512-block patches fit in 18 biased signed bits.
        long value = coordinate & 0x3ffffL;
        value = (value | value << 16) & 0x0000ffff0000ffffL;
        value = (value | value << 8) & 0x00ff00ff00ff00ffL;
        value = (value | value << 4) & 0x0f0f0f0f0f0f0f0fL;
        value = (value | value << 2) & 0x3333333333333333L;
        return (value | value << 1) & 0x5555555555555555L;
    }

    public record Ranked<T>(T value, long priority) { }
}
