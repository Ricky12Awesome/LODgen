package dev.lodgen.generation;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class GenerationScopeTest {
    @Test void preservesNormalRequestsAndOtherDimensions() {
        var owner = new Object();
        assertFalse(GenerationScope.isTransient(owner));
        try (var scope = new GenerationScope(owner, true)) {
            assertTrue(GenerationScope.isTransient(owner));
            assertFalse(GenerationScope.isTransient(new Object()));
            try (var normal = new GenerationScope(owner, false)) {
                assertFalse(GenerationScope.isTransient(owner));
            }
            assertTrue(GenerationScope.isTransient(owner));
        }
        assertFalse(GenerationScope.isTransient(owner));
    }

    @Test void restoresContextAfterFailedFeature() {
        var owner = new Object();
        assertThrows(IllegalStateException.class, () -> {
            try (var scope = new GenerationScope(owner, true)) { throw new IllegalStateException(); }
        });
        assertFalse(GenerationScope.isTransient(owner));
    }

    @Test void concurrentNormalGenerationDoesNotInheritOwnership() {
        var owner = new Object();
        try (var scope = new GenerationScope(owner, true)) {
            assertFalse(CompletableFuture.supplyAsync(() -> GenerationScope.isTransient(owner)).join());
        }
    }
}
