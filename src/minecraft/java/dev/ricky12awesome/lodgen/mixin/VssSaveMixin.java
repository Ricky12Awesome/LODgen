package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.LSSServerNetworking", remap = false)
public abstract class VssSaveMixin {
    @Inject(method = "onChunkSaveData", at = @At("HEAD"), cancellable = true)
    private static void lodgen$transientSave(ServerLevel level, ChunkAccess chunk, CallbackInfo callback) {
        // VSS observes serialization before our storage guard rejects transient saves.
        // Such an unload is not a world edit and must not discard its generated LOD.
        if (PersistenceRegistry.suppressUpdates(level, PersistenceRegistry.x(chunk.getPos()),
                PersistenceRegistry.z(chunk.getPos()))) callback.cancel();
    }
}
