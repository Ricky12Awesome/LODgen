package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SavePolicyTest {
    @Test void newClaimsStayEphemeralAcrossRepeatedRequests() {
        var policy = new SavePolicy();
        policy.claim(-1, -33, false);
        policy.claim(-1, -33, true); // Loaded by the preceding DH request.
        assertTrue(policy.suppress(-1, -33));
        assertFalse(policy.suppress(31, 31));
    }
    @Test void loadedChunksAndTheirEditsRemainSaveable() {
        var policy = new SavePolicy();
        policy.claim(10, 20, true);
        policy.claim(10, 20, false); // Including after unload.
        assertFalse(policy.suppress(10, 20));
    }
    @Test void normalGenerationAdoptsTheWholeDependencyArea() {
        var policy = new SavePolicy();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) policy.claim(x, z, false);
        policy.promoteArea(0, 0, 2);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) {
            assertEquals(Math.abs(x) > 2 || Math.abs(z) > 2, policy.suppress(x, z));
        }
        policy.claim(0, 0, false);
        assertFalse(policy.suppress(0, 0));
    }
    @Test void normalRequestBeforeTheHolderExistsCannotBeClaimed() {
        var policy = new SavePolicy();
        policy.promote(4096, -4096);
        policy.claim(4096, -4096, false);
        assertFalse(policy.suppress(4096, -4096));
    }
    @Test void promotionDoesNotAffectAnotherRegion() {
        var policy = new SavePolicy();
        policy.claim(31, 31, false);
        policy.claim(32, 31, false);
        policy.promote(31, 31);
        assertFalse(policy.suppress(31, 31));
        assertTrue(policy.suppress(32, 31));
    }
}
