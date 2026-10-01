package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class GenerationBoundsTest {
    @Test void smallerDistanceCancelsTheOldWaitingFutureButAllowsAFreshExpandedRequest() {
        var waiting = new ConcurrentHashMap<Long, CompletableFuture<Void>>();
        var old = new CompletableFuture<Void>();
        waiting.put(1L, old);
        boolean visible = GenerationBounds.overlaps(480 * 16, 0, 64, 0, 0, 128);
        assertFalse(GenerationBounds.retain(waiting, 1L, old, old, visible));
        assertTrue(old.isCancelled(), "Observers must be notified so stale retrieval bookkeeping can clear");
        assertTrue(waiting.isEmpty());
        var expanded = new CompletableFuture<Void>();
        waiting.put(1L, expanded);
        assertTrue(GenerationBounds.retain(waiting, 1L, expanded, expanded,
                GenerationBounds.overlaps(480 * 16, 0, 64, 0, 0, 512)));
        assertFalse(expanded.isDone());
    }

    @Test void neverCancelsDispatchedDataOrAReplacementQueuedAtTheSamePosition() {
        var waiting = new ConcurrentHashMap<Long, CompletableFuture<Void>>();
        var running = new CompletableFuture<Void>();
        assertFalse(GenerationBounds.retain(waiting, 1L, running, running, false));
        assertFalse(running.isDone(), "Native work retains pooled ownership");
        var replacement = new CompletableFuture<Void>();
        waiting.put(1L, replacement);
        assertFalse(GenerationBounds.retain(waiting, 1L, running, running, false));
        assertSame(replacement, waiting.get(1L));
        assertFalse(replacement.isDone());
    }

    @Test void shrinking512To128RejectsTheOldFrontierAndKeepsVisibleTerrain() {
        assertTrue(GenerationBounds.overlaps(480 * 16, 0, 64, 0, 0, 512));
        assertFalse(GenerationBounds.overlaps(480 * 16, 0, 64, 0, 0, 128));
        assertTrue(GenerationBounds.overlaps(124 * 16, 0, 64, 0, 0, 128));
        assertFalse(GenerationBounds.overlaps(128 * 16, 0, 64, 0, 0, 128));
    }

    @Test void retainsPartialEdgeSectionsAndTheSquareCorners() {
        assertTrue(GenerationBounds.overlaps(-2080, -2080, 64, 0, 0, 128));
        assertTrue(GenerationBounds.overlaps(2016, 2016, 64, 0, 0, 128));
        assertFalse(GenerationBounds.overlaps(-2112, 0, 64, 0, 0, 128));
    }

    @Test void movingOrExpandingCanRequestTerrainAgainAtNegativeAndBorderCoordinates() {
        assertFalse(GenerationBounds.overlaps(-10000, -10000, 64, 0, 0, 128));
        assertTrue(GenerationBounds.overlaps(-10000, -10000, 64, -10000, -10000, 128));
        assertTrue(GenerationBounds.overlaps(-8000, 0, 64, 0, 0, 512));
        assertTrue(GenerationBounds.overlaps(29999936, -30000000, 64, 30000000, -30000000, 128));
    }
}
