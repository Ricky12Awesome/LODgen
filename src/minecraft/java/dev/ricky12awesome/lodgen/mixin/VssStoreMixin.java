package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VssGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.RequestProcessingService", remap = false)
public abstract class VssStoreMixin {
    @Inject(method = "getLodStore", at = @At("RETURN"), cancellable = true)
    private void lodgen$store(CallbackInfoReturnable<Object> callback) {
        callback.setReturnValue(VssGeneration.store(this, callback.getReturnValue()));
    }

    @Inject(method = "invalidateStoreAllDimensions", at = @At("RETURN"), cancellable = true)
    private void lodgen$invalidate(CallbackInfoReturnable<Boolean> callback) {
        if (VssGeneration.invalidate(this)) callback.setReturnValue(true);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void lodgen$ready(CallbackInfo callback) { VssGeneration.broadcast(this); }

    @Inject(method = "shutdown", at = @At("TAIL"))
    private void lodgen$close(CallbackInfo callback) { VssGeneration.serviceStopped(this); }
}
