package dev.lodgen.task;

import dev.lodgen.generation.GenerationArea;

/** Stable compact 32x32 patches, exact square edges and at most 16 targets per
 * batch. Nearby requests reuse native generation dependencies before unloading.
 */
public final class SquarePlan {
    private static final int EDGE = GenerationArea.WORLD_EDGE_BLOCKS / 16;
    private final int minX, minZ, width, height, columns, rows;
    public SquarePlan(GenerationArea area) {
        if (area.totalRadius() < 1) throw new IllegalArgumentException("Generation radius must be positive");
        minX = (int) Math.max(-EDGE, (long) area.chunkX() - area.totalRadius());
        minZ = (int) Math.max(-EDGE, (long) area.chunkZ() - area.totalRadius());
        int maxX = (int) Math.min(EDGE, (long) area.chunkX() + area.totalRadius());
        int maxZ = (int) Math.min(EDGE, (long) area.chunkZ() + area.totalRadius());
        width = maxX - minX; height = maxZ - minZ;
        columns = (width + 3) / 4; rows = (height + 3) / 4;
    }
    public long batches() { return (long) columns * rows; }
    public long chunks() { return (long) width * height; }
    public Batch batch(long ordinal) {
        if (ordinal < 0 || ordinal >= batches()) throw new IllegalArgumentException("Invalid batch ordinal");
        long band = ordinal / (columns * 8L), within = ordinal % (columns * 8L);
        int bandRows = Math.min(8, rows - (int) band * 8);
        int patch = (int) (within / (8 * bandRows)), local = (int) (within % (8 * bandRows));
        int patchColumns = Math.min(8, columns - patch * 8);
        int column = patch * 8 + local % patchColumns, row = (int) band * 8 + local / patchColumns;
        return new Batch(minX + column * 4, minZ + row * 4, Math.min(4, width - column * 4), Math.min(4, height - row * 4));
    }
    public long chunksBefore(long prefix) {
        if (prefix < 0 || prefix > batches()) throw new IllegalArgumentException("Invalid completed prefix");
        if (prefix == batches()) return chunks();
        long band = prefix / (columns * 8L), within = prefix % (columns * 8L);
        int bandRows = Math.min(8, rows - (int) band * 8), bandHeight = Math.min(32, height - (int) band * 32);
        int patch = (int) (within / (8 * bandRows)), local = (int) (within % (8 * bandRows));
        int patchColumns = Math.min(8, columns - patch * 8), patchWidth = Math.min(32, width - patch * 32);
        int localRows = local / patchColumns;
        return band * 32 * width + (long) patch * 32 * bandHeight
                + (long) Math.min(bandHeight, localRows * 4) * patchWidth
                + (long) Math.min(4, bandHeight - localRows * 4) * Math.min(patchWidth, local % patchColumns * 4);
    }
    public record Batch(int x, int z, int width, int height) { public int chunks() { return width * height; } }
}
