package dev.lodgen.mixin;

import dev.lodgen.minecraft.PersistenceRegistry;
import dev.lodgen.minecraft.ChunkPersistence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkAccess.class)
public abstract class ChunkDirtyMixin {
    @Shadow @Final protected LevelHeightAccessor levelHeightAccessor;
    @Shadow @Final protected ChunkPos chunkPos;
    // Keep the actual dirty flag intact so adoption can save the same object.
    // #if MC_1211
    @Inject(method = "isUnsaved", at = @At("HEAD"), cancellable = true)
    // #else
    @Inject(method = {"isUnsaved", "tryMarkSaved"}, at = @At("HEAD"), cancellable = true)
    // #endif
    private void lodgen$skipSerialization(CallbackInfoReturnable<Boolean> ci) {
        if (levelHeightAccessor instanceof ServerLevel level && ChunkPersistence.suppress(PersistenceRegistry.get(level), chunkPos)) {
            ci.setReturnValue(false);
        }
    }
}
