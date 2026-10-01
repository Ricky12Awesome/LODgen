package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class SpatialGenerationOrderTest {
    @Test void nearerDistanceBandAlwaysWinsAcrossWorldBorder() {
        assertTrue(SpatialGenerationOrder.priority(63, 29999984, -29999984)
                < SpatialGenerationOrder.priority(64, 0, 0));
    }

    @Test void neighboringSectionsStayWithinOneLocalityPrefix() {
        long prefix = SpatialGenerationOrder.priority(100, 10240, -10752) >>> 6;
        // 8 sections per axis, a coherent 32-chunk patch, including negatives.
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            assertEquals(prefix, SpatialGenerationOrder.priority(100, 10240 + x * 64, -10752 + z * 64) >>> 6);
        }
    }

    @Test void negativeAndPositiveSectionsHaveDistinctStableKeys() {
        var priorities = new HashSet<Long>();
        for (int x = -16; x < 16; x++) for (int z = -16; z < 16; z++) {
            assertTrue(priorities.add(SpatialGenerationOrder.priority(100, x * 64, z * 64)));
        }
    }
}
