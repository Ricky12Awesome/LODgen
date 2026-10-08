package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VssGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Override runtime admission only; preserve the actual VSS configuration snapshot. */
@Pseudo
@Mixin(targets = "dev.vox.lss.common.config.ServerConfigBase", remap = false)
public abstract class VssGenerationMixin {
    @Inject(method = "enableChunkGeneration()Z", at = @At("HEAD"), cancellable = true)
    private void lodgen$enabled(CallbackInfoReturnable<Boolean> callback) { callback.setReturnValue(true); }
    @Inject(method = {"generationTimeoutTicks()I", "generationConcurrencyLimitGlobal()I", "generationConcurrencyLimitPerPlayer()I"}, at = @At("HEAD"), cancellable = true)
    private void lodgen$limit(CallbackInfoReturnable<Integer> callback) { callback.setReturnValue(Integer.MAX_VALUE); }
    @Inject(method = "generationLimits", at = @At("HEAD"), cancellable = true)
    private void lodgen$limits(CallbackInfoReturnable<Object> callback) { callback.setReturnValue(VssGeneration.generationLimits()); }
}
