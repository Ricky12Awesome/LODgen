package dev.lodgen.generation;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CaveDensityTest {
    @Test void stripsVanillaTerrainAndNoodleSelectorsWithoutMaskingFillMode() {
        var density = JsonParser.parseString("""
                {"type":"minecraft:min","left":{"type":"minecraft:interpolated","argument":{"type":"minecraft:range_choice","input":"minecraft:overworld/sloped_cheese","min_inclusive":0,"max_exclusive":1,"when_in_range":-1,"when_out_of_range":2}},"right":"minecraft:overworld/caves/noodle"}
                """);

        var result = CaveDensity.transform(density, false);

        assertTrue(result.supported());
        assertEquals(2, result.strippedSelectors());
        assertEquals(0, result.emptyMasks());
        assertEquals("minecraft:overworld/sloped_cheese", result.density().getAsJsonObject()
                .get("argument").getAsString());
    }

    @Test void emptyModeMasksInsideInterpolationAndRecognizesCachedInputReference() {
        var cached = JsonParser.parseString("""
                {"type":"minecraft:cache_once","input":"minecraft:overworld_large_biomes/sloped_cheese"}
                """);
        var density = JsonParser.parseString("""
                {"type":"minecraft:min","argument1":{"type":"minecraft:interpolated","input":{"type":"minecraft:range_choice","input":{"type":"minecraft:cache_once","input":"minecraft:overworld_large_biomes/sloped_cheese"},"min_inclusive":0,"max_exclusive":1,"when_in_range":-1,"when_out_of_range":2}},"argument2":"minecraft:overworld/caves/noodle"}
                """);

        var result = CaveDensity.transform(density, true);

        assertTrue(result.supported());
        assertEquals(cached, result.terrain());
        assertEquals(1, result.emptyMasks());
        var masked = result.density().getAsJsonObject().getAsJsonObject("input");
        assertEquals("minecraft:range_choice", masked.get("type").getAsString());
        assertEquals(cached, masked.get("input"));
        assertEquals(-1.0, masked.get("when_in_range").getAsDouble());
        assertEquals(cached, masked.get("when_out_of_range"));
    }

    @Test void leavesUnknownTerrainFormulaUnsupportedForNormalGenerationFallback() {
        var density = JsonParser.parseString("""
                {"type":"minecraft:add","argument1":"minecraft:overworld/continents","argument2":0.25}
                """);

        var result = CaveDensity.transform(density, false);

        assertFalse(result.supported());
        assertNull(result.terrain());
        assertEquals(0, result.strippedSelectors());
    }
}
