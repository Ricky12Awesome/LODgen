package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class GenerationFrontierTest {
    @Test void distantDispatchRemainsCompactAndStartsAtTheCenterTile() {
        var frontier = new GenerationFrontier(-3, 5, 256);
        assertEquals(new GenerationFrontier.Tile(-4, 4), frontier.next());
        frontier = new GenerationFrontier(-3, 5, 256);
        for (int patch = 0; patch < 20; patch++) {
            var tiles = new HashSet<Long>();
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < 64; i++) {
                var tile = frontier.next();
                assertNotNull(tile); assertTrue(tiles.add(tile.key()));
                minX = Math.min(minX, tile.x()); minZ = Math.min(minZ, tile.z());
                maxX = Math.max(maxX, tile.x()); maxZ = Math.max(maxZ, tile.z());
            }
            assertEquals(28, maxX - minX); assertEquals(28, maxZ - minZ);
        }
    }
    @Test void visitsEveryIntersectingTileOnceAndTerminates() {
        for (int center : new int[]{0, -3, 63}) {
            var frontier = new GenerationFrontier(center, -center, 64);
            var actual = new HashSet<Long>();
            while (!frontier.exhausted()) {
                var tile = frontier.next();
                if (tile != null) assertTrue(actual.add(tile.key()), "Duplicate tile");
            }
            var expected = new HashSet<Long>();
            for (int x = center - 72; x <= center + 72; x++) for (int z = -center - 72; z <= -center + 72; z++) {
                if (x % 4 != 0 || z % 4 != 0) continue;
                var tile = new GenerationFrontier.Tile(x, z);
                if (GenerationFrontier.contains(tile, center, -center, 64)) expected.add(tile.key());
            }
            assertEquals(expected, actual);
        }
    }

    @Test void smallerDistanceExcludesOldFrontierAndExpansionFindsNewTerrain() {
        var tile = new GenerationFrontier.Tile(96, 0);
        assertTrue(GenerationFrontier.contains(tile, 0, 0, 128));
        assertFalse(GenerationFrontier.contains(tile, 0, 0, 64));
        assertEquals(tile, GenerationFrontier.Tile.fromKey(tile.key()));
        var border = new GenerationFrontier(1_874_999, -1_874_999, 8);
        while (!border.exhausted()) {
            var next = border.next();
            if (next != null) assertTrue(next.x() + 4 <= 1_875_000 && next.z() >= -1_875_000);
        }
    }
}
