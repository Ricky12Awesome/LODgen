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
    private final GenerationArea area;
    public SquarePlan(GenerationArea area) {
        this.area = area;
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
    /** Every patch ring before the first unfinished batch is complete. This
     * recognizes a completed inner radius without enumerating its chunks.
     */
    public boolean completedArea(long prefix, GenerationArea next) {
        if (prefix < 0 || prefix > batches()) throw new IllegalArgumentException("Invalid completed prefix");
        long dx = Math.abs((long) next.chunkX() - area.chunkX()), dz = Math.abs((long) next.chunkZ() - area.chunkZ());
        if (next.radius() > 0 && (dx + next.radius() > area.radius() || dz + next.radius() > area.radius())) return false;
        if (next.savedRadius() > 0 && (dx + next.savedRadius() > area.savedRadius() || dz + next.savedRadius() > area.savedRadius())) return false;
        int x1 = (int) Math.max(-EDGE, (long) next.chunkX() - next.totalRadius());
        int z1 = (int) Math.max(-EDGE, (long) next.chunkZ() - next.totalRadius());
        int x2 = (int) Math.min(EDGE, (long) next.chunkX() + next.totalRadius()) - 1;
        int z2 = (int) Math.min(EDGE, (long) next.chunkZ() + next.totalRadius()) - 1;
        if (x1 < minX || z1 < minZ || x2 >= minX + width || z2 >= minZ + height) return false;
        if (prefix == batches()) return true;
        var unfinished = patches.locate(prefix);
        int cx = centerColumn / 8, cz = centerRow / 8;
        int incompleteRing = Math.max(Math.abs(unfinished.x - cx), Math.abs(unfinished.z - cz));
        int neededRing = Math.max(Math.max(Math.abs((x1 - minX) / 32 - cx), Math.abs((x2 - minX) / 32 - cx)),
                Math.max(Math.abs((z1 - minZ) / 32 - cz), Math.abs((z2 - minZ) / 32 - cz)));
        return neededRing < incompleteRing;
    }
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
