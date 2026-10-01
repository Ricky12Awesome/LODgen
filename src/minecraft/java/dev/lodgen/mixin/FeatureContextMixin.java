package dev.lodgen.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.lodgen.generation.GenerationScope;
import dev.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;

/** Structure entity initialization (e.g. village cats) calls ServerLevel's
 * chunk API while FEATURES runs. Those reads must inherit the owning generator,
 * rather than accidentally adopting a DH-only chunk and its whole save area.
 */
@Mixin(ChunkGenerator.class)
public abstract class FeatureContextMixin {
    @WrapMethod(method = "applyBiomeDecoration")
    private void lodgen$featureLookups(WorldGenLevel region, ChunkAccess chunk, StructureManager structures,
                                      Operation<Void> original) {
        var policy = PersistenceRegistry.get(region.getLevel());
        try (var scope = new GenerationScope(policy, PersistenceRegistry.suppress(policy, chunk.getPos()))) {
            original.call(region, chunk, structures);
        }
    }
}
