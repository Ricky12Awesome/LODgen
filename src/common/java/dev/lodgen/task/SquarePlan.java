package dev.lodgen.task;

import dev.lodgen.generation.GenerationArea;
import dev.lodgen.generation.CenterOutGrid;

/** Stable compact 32x32 patches, exact square edges and at most 16 targets per
 * batch. Nearby requests reuse native generation dependencies before unloading.
 */
public final class SquarePlan {
    private static final int EDGE = GenerationArea.WORLD_EDGE_BLOCKS / 16;
    private final int minX, minZ, width, height, columns, rows, centerColumn, centerRow;
    private final CenterOutGrid patches;
    public SquarePlan(GenerationArea area) {
        if (area.totalRadius() < 1) throw new IllegalArgumentException("Generation radius must be positive");
        minX = (int) Math.max(-EDGE, (long) area.chunkX() - area.totalRadius());
        minZ = (int) Math.max(-EDGE, (long) area.chunkZ() - area.totalRadius());
        int maxX = (int) Math.min(EDGE, (long) area.chunkX() + area.totalRadius());
        int maxZ = (int) Math.min(EDGE, (long) area.chunkZ() + area.totalRadius());
        width = maxX - minX; height = maxZ - minZ;
        columns = (width + 3) / 4; rows = (height + 3) / 4;
        centerColumn = Math.clamp((area.chunkX() - minX) / 4, 0, columns - 1);
        centerRow = Math.clamp((area.chunkZ() - minZ) / 4, 0, rows - 1);
        patches = new CenterOutGrid(columns, rows, 8, centerColumn / 8, centerRow / 8);
    }
    public long batches() { return (long) columns * rows; }
    public long chunks() { return (long) width * height; }
    public Batch batch(long ordinal) {
        if (ordinal < 0 || ordinal >= batches()) throw new IllegalArgumentException("Invalid batch ordinal");
        var patch = patches.locate(ordinal);
        var local = local(patch).locate(patch.offset);
        int column = patch.x * 8 + local.x, row = patch.z * 8 + local.z;
        return new Batch(minX + column * 4, minZ + row * 4, Math.min(4, width - column * 4), Math.min(4, height - row * 4));
    }
    public long chunksBefore(long prefix) {
        if (prefix < 0 || prefix > batches()) throw new IllegalArgumentException("Invalid completed prefix");
        if (prefix == batches()) return chunks();
        var patch = patches.locate(prefix);
        var cell = local(patch).locate(patch.offset);
        return patch.weightBefore(width, height, 32)
                + cell.weightBefore(Math.min(32, width - patch.x * 32), Math.min(32, height - patch.z * 32), 4);
    }
    private CenterOutGrid local(CenterOutGrid.Cell patch) {
        return new CenterOutGrid(Math.min(8, columns - patch.x * 8), Math.min(8, rows - patch.z * 8), 1,
                centerColumn - patch.x * 8, centerRow - patch.z * 8);
    }
    public record Batch(int x, int z, int width, int height) { public int chunks() { return width * height; } }
}
