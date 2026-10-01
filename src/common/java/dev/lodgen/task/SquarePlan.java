package dev.lodgen.task;

import dev.lodgen.generation.GenerationArea;

/** Stable row order, exact square edges, and at most 16 chunks per native batch. */
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
        int column = (int) (ordinal % columns), row = (int) (ordinal / columns);
        return new Batch(minX + column * 4, minZ + row * 4, Math.min(4, width - column * 4), Math.min(4, height - row * 4));
    }
    public long chunksBefore(long prefix) {
        if (prefix < 0 || prefix > batches()) throw new IllegalArgumentException("Invalid completed prefix");
        long completeRows = prefix / columns;
        long result = Math.min(height, completeRows * 4) * width;
        if (prefix < batches()) result += Math.min(4, height - completeRows * 4) * Math.min(width, prefix % columns * 4);
        return result;
    }
    public record Batch(int x, int z, int width, int height) { public int chunks() { return width * height; } }
}
