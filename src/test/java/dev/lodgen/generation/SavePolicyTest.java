package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SavePolicyTest {
    @Test void rectangleClaimsReuseDependenciesAndKeepNormalOwnership() {
        var policy = new SavePolicy();
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        policy.claimArea(-33, -2, 67, 5, key -> { lookups.incrementAndGet(); return key == 0; });
        assertEquals(335, lookups.get());
        policy.promote(-1, -1);
        policy.saveGenerated(1, 1);
        policy.claimArea(-33, -2, 67, 5, key -> { fail("Known dependency queried twice"); return false; });
        assertFalse(policy.suppress(0, 0));
        assertFalse(policy.suppress(-1, -1));
        assertFalse(policy.suppress(1, 1));
        assertTrue(policy.generated(1, 1));
        assertTrue(policy.suppress(-33, -2));
        assertTrue(policy.suppress(33, 2));
    }
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
    @Test void savedLodTargetsDoNotAdoptTheirSupportingChunks() {
        var policy = new SavePolicy();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) policy.claim(x, z, false);
        policy.saveGenerated(0, 0);
        assertFalse(policy.suppress(0, 0));
        assertTrue(policy.generated(0, 0), "Feature lookups must keep transient ownership outside the saved target");
        assertTrue(policy.suppress(1, 0));
        policy.normalRequest(0, 0, 2);
        assertFalse(policy.generated(0, 0));
        assertFalse(policy.suppress(1, 0), "Normal player/Chunky requests still adopt supporting terrain");
        assertTrue(policy.suppress(3, 0));
    }
}
