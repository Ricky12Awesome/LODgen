package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import static dev.lodgen.generation.GenerationPolicy.Plan.*;
import static org.junit.jupiter.api.Assertions.*;

class GenerationPolicyTest {
    @Test void dhChunkPhasesUseNativePathWithAddonAutomaticToggleOff() {
        for (boolean enabled : new boolean[]{false, true}) for (var plan : new GenerationPolicy.Plan[]{SURFACE_THEN_CHUNKS, CHUNKS_ONLY}) {
            var policy = new GenerationPolicy(enabled, plan, true);
            assertTrue(policy.dhChunks());
            assertTrue(policy.anyAutomatic());
            assertEquals(enabled, policy.extraDhChunks(true));
            assertFalse(policy.extraDhChunks(false));
            assertFalse(new GenerationPolicy(enabled, plan, false).dhChunks(), "Respect non-FEATURES chunk modes");
        }
    }
    @Test void surfaceOnlyKeepsDhSurfaceAndOptInChunkJobs() {
        for (boolean enabled : new boolean[]{false, true}) {
            var policy = new GenerationPolicy(enabled, SURFACE_ONLY, true);
            assertTrue(policy.surfaceFirst());
            assertFalse(policy.dhChunks(), "Surface requests must retain the rough generator");
            assertEquals(enabled, policy.extraDhChunks(false));
            assertEquals(enabled, policy.extraDhChunks(true));
        }
    }
    @Test void disabledBlocksAllAutomaticGenerationIncludingVoxyAndSaving() {
        for (boolean enabled : new boolean[]{false, true}) {
            var policy = new GenerationPolicy(enabled, DISABLED, true);
            assertFalse(policy.anyAutomatic()); assertFalse(policy.automatic());
            assertFalse(policy.dhChunks()); assertFalse(policy.extraDhChunks(true));
            assertEquals(enabled, new GenerationPolicy(enabled, NO_DH, false).automatic());
        }
    }
}
