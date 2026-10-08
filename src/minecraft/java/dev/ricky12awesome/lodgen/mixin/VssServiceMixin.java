package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VssGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.RequestProcessingService", remap = false)
public abstract class VssServiceMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void lodgen$cacheCutover(CallbackInfo callback) {
        if (!VssGeneration.serviceReady(this)) callback.cancel();
    }
    @Inject(method = "shutdown", at = @At("TAIL"))
    private void lodgen$stopping(CallbackInfo callback) {
        VssGeneration.serviceStopped(this);
    }
}
