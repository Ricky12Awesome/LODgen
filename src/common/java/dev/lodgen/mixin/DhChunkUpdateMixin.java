package dev.lodgen.mixin;

import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import dev.lodgen.minecraft.PersistenceRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native FULL load/unload events also reach DH's ordinary LOD update queue.
 * FEATURES already converts these chunks, so don't light, convert and store
 * them a second time. Normal adoption immediately enables updates again.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.SharedApi", remap = false)
public abstract class DhChunkUpdateMixin {
    @Inject(method = "applyChunkUpdate", at = @At("HEAD"), cancellable = true)
    private void lodgen$skipTransientUpdate(IChunkWrapper chunk, ILevelWrapper level, boolean waitForLoadedWorld,
                                           CallbackInfo callback) {
        if (level instanceof IServerLevelWrapper) {
            var pos = chunk.getChunkPos();
            if (PersistenceRegistry.suppressUpdates(level.getWrappedMcObject(), pos.getX(), pos.getZ())) callback.cancel();
        }
    }
}
