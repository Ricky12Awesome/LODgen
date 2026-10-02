package dev.lodgen.generation;

/** Nearby 32x32 patches of 4x4 tiles. Compact requests reuse dependencies even
 * far from the center, instead of widening a thin ring indefinitely.
 */
public final class GenerationFrontier {
    public static final int WIDTH = 4;
    private static final int PATCH_WIDTH = 32;
    private static final int[] PATCH_ORDER = patchOrder();
    private static final int WORLD_EDGE = 1_875_000;
    private final int centerX, centerZ, radius, baseX, baseZ;
    private final long count;
    private long visited;
    private int x, z, dx = 0, dz = -1;

    public GenerationFrontier(int centerX, int centerZ, int radius) {
        if (radius < 1) throw new IllegalArgumentException("Radius must be positive");
        this.centerX = centerX; this.centerZ = centerZ; this.radius = radius;
        baseX = Math.floorDiv(centerX, WIDTH) * WIDTH - PATCH_WIDTH / 2;
        baseZ = Math.floorDiv(centerZ, WIDTH) * WIDTH - PATCH_WIDTH / 2;
        long diameter = 2 * (((long) radius + PATCH_WIDTH - 1) / PATCH_WIDTH + 1) + 1;
        count = diameter * diameter * PATCH_ORDER.length;
    }

    /** Call with a fixed budget per tick; null can mean a tile outside the bounds. */
    public Tile next() {
        if (exhausted()) return null;
        int local = PATCH_ORDER[(int) (visited % PATCH_ORDER.length)];
        long minX = (long) baseX + (long) x * PATCH_WIDTH + (local & 7) * WIDTH;
        long minZ = (long) baseZ + (long) z * PATCH_WIDTH + (local >> 3) * WIDTH;
        if (++visited % PATCH_ORDER.length == 0) {
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int oldDx = dx; dx = -dz; dz = oldDx;
            }
            x += dx; z += dz;
        }
        if (minX < -WORLD_EDGE || minZ < -WORLD_EDGE || minX + WIDTH > WORLD_EDGE || minZ + WIDTH > WORLD_EDGE) return null;
        var tile = new Tile((int) minX, (int) minZ);
        return contains(tile, centerX, centerZ, radius) ? tile : null;
    }

    public boolean exhausted() { return visited >= count; }
    private static int[] patchOrder() {
        int[] order = new int[64];
        int x = 0, z = 0, dx = 0, dz = -1, count = 0;
        // Visit a fixed 8x8 tile patch from its center outward.
        for (int step = 0; step < 81; step++) {
            if (x >= -4 && x < 4 && z >= -4 && z < 4) order[count++] = (x + 4) | (z + 4) << 3;
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int oldDx = dx; dx = -dz; dz = oldDx;
            }
            x += dx; z += dz;
        }
        return order;
    }
    public static boolean contains(Tile tile, int centerX, int centerZ, int radius) {
        return GenerationBounds.overlaps(tile.x * 16, tile.z * 16, WIDTH * 16, centerX * 16, centerZ * 16, radius);
    }
    public record Tile(int x, int z) {
        public long key() { return (x & 0xffffffffL) | (long) z << 32; }
        public static Tile fromKey(long key) { return new Tile((int) key, (int) (key >> 32)); }
    }
}
