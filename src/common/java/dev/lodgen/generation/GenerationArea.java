package dev.lodgen.generation;

/** Square chunk ranges have inclusive minimum and exclusive maximum edges. */
public record GenerationArea(int blockX, int blockZ, int radius, int savedRadius) {
    public static final int WORLD_EDGE_BLOCKS = 30_000_000;
    public static final int MAX_RADIUS = WORLD_EDGE_BLOCKS / 8;
    public GenerationArea {
        if (Math.abs((long) blockX) > WORLD_EDGE_BLOCKS || Math.abs((long) blockZ) > WORLD_EDGE_BLOCKS)
            throw new IllegalArgumentException("Center is outside Minecraft's world bounds");
        if (radius < 0 || radius > MAX_RADIUS || savedRadius < 0 || savedRadius > MAX_RADIUS)
            throw new IllegalArgumentException("Radius must be 0–" + MAX_RADIUS + " chunks");
    }
    public int chunkX() { return Math.floorDiv(blockX, 16); }
    public int chunkZ() { return Math.floorDiv(blockZ, 16); }
    public int totalRadius() { return Math.max(radius, savedRadius); }
    public boolean lods(int x, int z) { return contains(x, z, radius); }
    public boolean saves(int x, int z) { return contains(x, z, savedRadius); }
    private boolean contains(int x, int z, int range) {
        return range > 0 && (long) x >= (long) chunkX() - range && (long) x < (long) chunkX() + range
                && (long) z >= (long) chunkZ() - range && (long) z < (long) chunkZ() + range;
    }
}
