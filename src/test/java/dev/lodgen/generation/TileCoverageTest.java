package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class TileCoverageTest {
    @TempDir Path directory;
    @Test void completedTilesRoundTripAndIncompleteCoverageIsRejected() throws Exception {
        Path file = directory.resolve("world.tiles");
        assertTrue(TileCoverage.read(file).isEmpty());
        var tiles = Set.of(new GenerationFrontier.Tile(-64, 128).key(), new GenerationFrontier.Tile(60, -128).key());
        TileCoverage.write(file, tiles);
        assertEquals(tiles, TileCoverage.read(file));
        Files.write(file, new byte[]{0, 0, 0, 0, 1});
        assertThrows(java.io.IOException.class, () -> TileCoverage.read(file));
    }
}
