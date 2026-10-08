package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VssUpdatesTest {
    @Test void readinessInvalidationDoesNotExemptAnEditOfTheSamePositions() {
        var updates = new VssUpdates();
        updates.committed(List.of(column("overworld", 1), column("overworld", 1), column("nether", 1)));
        var batches = updates.drain();
        assertEquals(2, batches.size());
        var batch = batches.getFirst();
        assertArrayEquals(new long[]{1}, batch.positions());
        assertTrue(updates.preserves("overworld", batch.positions()));
        assertFalse(updates.preserves("overworld", batch.positions().clone()), "genuine edits remain destructive");
        assertFalse(updates.preserves("nether", batch.positions()));
        assertTrue(updates.drain().isEmpty());
        assertTrue(updates.preserves("overworld", batch.positions()), "VSS can replay an applied mailbox event");
    }

    @Test void rangeFilteringPaginatesEveryEligibleColumnIncludingNegativeCoordinates() {
        var positions = new ArrayList<Long>();
        for (int x = -8; x <= 8; x++) positions.add(position(x, -4));
        positions.add(position(0, 7));
        positions.add(position(Integer.MAX_VALUE, Integer.MIN_VALUE));
        var pages = VssUpdates.inRange(positions.stream().mapToLong(Long::longValue).toArray(), -2, -4, 4, 3);
        assertEquals(3, pages.size());
        assertArrayEquals(new long[]{position(-6, -4), position(-5, -4), position(-4, -4)}, pages.get(0));
        assertArrayEquals(new long[]{position(-3, -4), position(-2, -4), position(-1, -4)}, pages.get(1));
        assertArrayEquals(new long[]{position(0, -4), position(1, -4), position(2, -4)}, pages.get(2));
        assertTrue(VssUpdates.inRange(new long[0], 0, 0, 4, 3).isEmpty());
    }

    private static VssColumns.Column column(String dimension, long position) {
        return new VssColumns.Column(dimension, position, new byte[]{1}, 1, 1);
    }

    private static long position(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
}
