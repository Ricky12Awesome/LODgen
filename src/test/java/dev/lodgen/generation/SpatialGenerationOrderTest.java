package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SpatialGenerationOrderTest {
    @Test void centerPatchAndNearestSectionsWinAcrossWorldBorder() {
        for (int center : new int[]{0, -16384, 29_999_000, -29_999_000}) {
            long first = SpatialGenerationOrder.priority(center + 32, center + 32, center, center);
            assertTrue(first < SpatialGenerationOrder.priority(center - 224, center - 224, center, center));
            assertTrue(first < SpatialGenerationOrder.priority(center - 480, center - 480, center, center));
        }
        assertTrue(SpatialGenerationOrder.priority(-29_999_984, -29_999_984, -29_999_984, -29_999_984)
                < SpatialGenerationOrder.priority(29_999_984, 29_999_984, -29_999_984, -29_999_984));
    }

    @Test void neighboringSectionsStayWithinOneLocalityPrefix() {
        long prefix = SpatialGenerationOrder.priority(10240, -10752, 10496, -10496) >>> 10;
        // 8 sections per axis, a coherent 32-chunk patch, including negatives.
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            assertEquals(prefix, SpatialGenerationOrder.priority(10240 + x * 64, -10752 + z * 64, 10496, -10496) >>> 10);
        }
    }

    @Test void negativeAndPositiveSectionsHaveDistinctStableKeys() {
        var priorities = new HashSet<Long>();
        for (int x = -16; x < 16; x++) for (int z = -16; z < 16; z++) {
            assertTrue(priorities.add(SpatialGenerationOrder.priority(x * 64, z * 64, 0, 0)));
        }
    }
}
