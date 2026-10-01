package dev.lodgen.generation;

/** Incremental nearest-first 4x4 chunk tiles. No list proportional to radius. */
public final class GenerationFrontier {
    public static final int WIDTH = 4;
    private static final int WORLD_EDGE = 1_875_000;
    private final int centerX, centerZ, radius, baseX, baseZ;
    private final long count;
    private long visited;
    private int x, z, dx = 0, dz = -1;

    public GenerationFrontier(int centerX, int centerZ, int radius) {
        if (radius < 1) throw new IllegalArgumentException("Radius must be positive");
        this.centerX = centerX; this.centerZ = centerZ; this.radius = radius;
        baseX = Math.floorDiv(centerX, WIDTH); baseZ = Math.floorDiv(centerZ, WIDTH);
        long diameter = 2 * (((long) radius + WIDTH - 1) / WIDTH + 1) + 1;
        count = diameter * diameter;
    }

    /** Call with a fixed budget per tick; null can mean a tile outside the bounds. */
    public Tile next() {
        if (exhausted()) return null;
        long minX = ((long) baseX + x) * WIDTH, minZ = ((long) baseZ + z) * WIDTH;
        if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
            int oldDx = dx; dx = -dz; dz = oldDx;
        }
        x += dx; z += dz; visited++;
        if (minX < -WORLD_EDGE || minZ < -WORLD_EDGE || minX + WIDTH > WORLD_EDGE || minZ + WIDTH > WORLD_EDGE) return null;
        var tile = new Tile((int) minX, (int) minZ);
        return contains(tile, centerX, centerZ, radius) ? tile : null;
    }

    public boolean exhausted() { return visited >= count; }
    public static boolean contains(Tile tile, int centerX, int centerZ, int radius) {
        return GenerationBounds.overlaps(tile.x * 16, tile.z * 16, WIDTH * 16, centerX * 16, centerZ * 16, radius);
    }
    public record Tile(int x, int z) {
        public long key() { return (x & 0xffffffffL) | (long) z << 32; }
        public static Tile fromKey(long key) { return new Tile((int) key, (int) (key >> 32)); }
    }
}
