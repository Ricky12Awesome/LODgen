package dev.lodgen.generation;

import java.util.HashMap;
import java.util.Map;

/** Compact coverage counts: full 128x128-chunk cells cost one lookup; partial
 * cells use row masks. Moving/shrinking a radius never counts outside coverage.
 */
public final class TileProgress {
    private static final int EDGE = GenerationArea.WORLD_EDGE_BLOCKS / 16 / GenerationFrontier.WIDTH;
    private final Map<Long, Cell> cells = new HashMap<>();
    private static final class Cell { final int[] rows = new int[32]; int count; }

    public synchronized void complete(GenerationFrontier.Tile tile) {
        int x = Math.floorDiv(tile.x(), GenerationFrontier.WIDTH), z = Math.floorDiv(tile.z(), GenerationFrontier.WIDTH);
        long key = (Math.floorDiv(x, 32) & 0xffffffffL) | (long) Math.floorDiv(z, 32) << 32;
        var cell = cells.computeIfAbsent(key, ignored -> new Cell());
        int row = z & 31, mask = 1 << (x & 31);
        if ((cell.rows[row] & mask) == 0) { cell.rows[row] |= mask; cell.count++; }
    }

    public synchronized long remainingChunks(int centerX, int centerZ, int radius) {
        if (radius < 1) return -1;
        int minX = (int) Math.max(-EDGE, Math.floorDiv((long) centerX - radius, 4));
        int minZ = (int) Math.max(-EDGE, Math.floorDiv((long) centerZ - radius, 4));
        int maxX = (int) Math.min(EDGE, Math.floorDiv((long) centerX + radius + 3, 4));
        int maxZ = (int) Math.min(EDGE, Math.floorDiv((long) centerZ + radius + 3, 4));
        long completed = 0;
        for (var entry : cells.entrySet()) {
            int cellX = (int) (long) entry.getKey() * 32, cellZ = (int) (entry.getKey() >> 32) * 32;
            int fromX = Math.max(0, minX - cellX), fromZ = Math.max(0, minZ - cellZ);
            int toX = Math.min(32, maxX - cellX), toZ = Math.min(32, maxZ - cellZ);
            if (fromX >= toX || fromZ >= toZ) continue;
            var cell = entry.getValue();
            if (fromX == 0 && toX == 32 && fromZ == 0 && toZ == 32) completed += cell.count;
            else {
                int mask = (-1 >>> (32 - (toX - fromX))) << fromX;
                for (int row = fromZ; row < toZ; row++) completed += Integer.bitCount(cell.rows[row] & mask);
            }
        }
        return ((long) (maxX - minX) * (maxZ - minZ) - completed) * 16;
    }
}
