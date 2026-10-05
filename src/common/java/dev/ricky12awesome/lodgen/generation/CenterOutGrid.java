package dev.ricky12awesome.lodgen.generation;

/** Random access to clipped square rings with weighted edge cells. Keeps even
 * world-sized plans bounded in memory and avoids scanning skipped outer cells.
 */
public final class CenterOutGrid {
    private final int width, height, step, columns, rows, centerX, centerZ, maxRing;
    public CenterOutGrid(int width, int height, int step, int centerX, int centerZ) {
        this.width = width; this.height = height; this.step = step;
        columns = (width + step - 1) / step; rows = (height + step - 1) / step;
        this.centerX = Math.clamp(centerX, 0, columns - 1);
        this.centerZ = Math.clamp(centerZ, 0, rows - 1);
        maxRing = Math.max(Math.max(this.centerX, columns - 1 - this.centerX), Math.max(this.centerZ, rows - 1 - this.centerZ));
    }
    public Cell locate(long ordinal) {
        if (ordinal < 0 || ordinal >= (long) width * height) throw new IllegalArgumentException("Invalid grid ordinal");
        int low = 0, high = maxRing;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (inside(mid, width, height, step) > ordinal) high = mid; else low = mid + 1;
        }
        int ring = low;
        long local = ordinal - inside(ring - 1, width, height, step);
        if (ring == 0) return new Cell(centerX, centerZ, local, ring, -1);
        for (int side = 0; side < 4; side++) {
            var bounds = side(ring, side);
            long size = bounds.weight(width, height, step);
            if (local >= size) { local -= size; continue; }
            boolean horizontal = side % 2 != 0, reversed = side == 1 || side == 2;
            int begin = horizontal ? bounds.x1 : bounds.z1, end = horizontal ? bounds.x2 : bounds.z2;
            int a = begin, b = end - 1;
            while (a < b) {
                int mid = (a + b) >>> 1;
                long count = (horizontal ? new Bounds(begin, bounds.z1, mid + 1, bounds.z2)
                        : new Bounds(bounds.x1, begin, bounds.x2, mid + 1)).weight(width, height, step);
                long forward = reversed ? size - 1 - local : local;
                if (count > forward) b = mid; else a = mid + 1;
            }
            int x = horizontal ? a : bounds.x1, z = horizontal ? bounds.z1 : a;
            long before = previousCells(bounds, x, z, side).weight(width, height, step);
            return new Cell(x, z, local - before, ring, side);
        }
        throw new IllegalStateException("Missing ring cell");
    }
    private long inside(int ring, int spanX, int spanZ, int cellStep) {
        if (ring < 0) return 0;
        return new Bounds(Math.max(0, centerX - ring), Math.max(0, centerZ - ring),
                Math.min(columns, centerX + ring + 1), Math.min(rows, centerZ + ring + 1)).weight(spanX, spanZ, cellStep);
    }
    private Bounds side(int r, int side) {
        int x = centerX, z = centerZ;
        var raw = switch (side) {
            case 0 -> new Bounds(x + r, z - r, x + r + 1, z + r);
            case 1 -> new Bounds(x - r + 1, z + r, x + r + 1, z + r + 1);
            case 2 -> new Bounds(x - r, z - r + 1, x - r + 1, z + r + 1);
            default -> new Bounds(x - r, z - r, x + r, z - r + 1);
        };
        return new Bounds(Math.clamp(raw.x1, 0, columns), Math.clamp(raw.z1, 0, rows),
                Math.clamp(raw.x2, 0, columns), Math.clamp(raw.z2, 0, rows));
    }
    private static Bounds previousCells(Bounds bounds, int x, int z, int side) {
        return switch (side) {
            case 0 -> new Bounds(bounds.x1, bounds.z1, bounds.x2, z);
            case 1 -> new Bounds(x + 1, bounds.z1, bounds.x2, bounds.z2);
            case 2 -> new Bounds(bounds.x1, z + 1, bounds.x2, bounds.z2);
            default -> new Bounds(bounds.x1, bounds.z1, x, bounds.z2);
        };
    }
    private record Bounds(int x1, int z1, int x2, int z2) {
        long weight(int width, int height, int step) {
            return (long) (Math.min(width, x2 * step) - Math.min(width, x1 * step))
                    * (Math.min(height, z2 * step) - Math.min(height, z1 * step));
        }
    }
    public final class Cell {
        public final int x, z;
        public final long offset;
        private final int ring, side;
        private Cell(int x, int z, long offset, int ring, int side) { this.x = x; this.z = z; this.offset = offset; this.ring = ring; this.side = side; }
        public long weightBefore(int spanX, int spanZ, int cellStep) {
            long result = inside(ring - 1, spanX, spanZ, cellStep);
            for (int i = 0; i < side; i++) result += side(ring, i).weight(spanX, spanZ, cellStep);
            if (side >= 0) result += previousCells(side(ring, side), x, z, side).weight(spanX, spanZ, cellStep);
            return result;
        }
    }
}
