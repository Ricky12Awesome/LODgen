package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class TileProgressTest {
    @Test void countsRoundedTilesAndExcludesCoverageOutsideTheCurrentRadius() {
        var coverage = new TileProgress();
        assertEquals(64, coverage.remainingChunks(0, 0, 1));
        var tile = new GenerationFrontier.Tile(0, 0);
        coverage.complete(tile); coverage.complete(tile);
        assertEquals(48, coverage.remainingChunks(0, 0, 1));
        assertEquals(64, coverage.remainingChunks(128, 128, 1));
        assertEquals(16368, coverage.remainingChunks(0, 0, 64));
        assertEquals(48, coverage.remainingChunks(0, 0, 1));
    }

    @Test void fullCellsAndPartialNegativeSlicesMatchActualFrontierTargets() {
        var coverage = new TileProgress(); var complete = new HashSet<Long>();
        for (int x = -128; x < 128; x += 4) for (int z = -128; z < 128; z += 4) {
            var tile = new GenerationFrontier.Tile(x, z);
            if (x < 0 || (x & 7) == 0) { coverage.complete(tile); complete.add(tile.key()); }
        }
        for (int center : new int[]{-65, -3, 0, 33, 128}) for (int radius : new int[]{1, 17, 64, 129}) {
            var frontier = new GenerationFrontier(center, -center, radius);
            long remaining = 0;
            while (!frontier.exhausted()) {
                var tile = frontier.next();
                if (tile != null && !complete.contains(tile.key())) remaining += 16;
            }
            assertEquals(remaining, coverage.remainingChunks(center, -center, radius));
        }
    }

    @Test void clipsWorldEdgesLikeTheGenerator() {
        var coverage = new TileProgress();
        assertEquals(16, coverage.remainingChunks(1_874_999, -1_874_999, 1));
        coverage.complete(new GenerationFrontier.Tile(1_874_996, -1_875_000));
        assertEquals(0, coverage.remainingChunks(1_874_999, -1_874_999, 1));
        assertEquals(-1, coverage.remainingChunks(0, 0, 0));
    }
}
