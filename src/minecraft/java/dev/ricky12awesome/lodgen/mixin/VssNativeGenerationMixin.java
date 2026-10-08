package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VssGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ticket handoff transformation is applied by LodgenMixinPlugin. */
@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.ChunkGenerationService", remap = false)
public abstract class VssNativeGenerationMixin {
    @Inject(method = "shutdown", at = @At("TAIL"))
    private void lodgen$close(CallbackInfo callback) { VssGeneration.serviceStopped(this); }
}
