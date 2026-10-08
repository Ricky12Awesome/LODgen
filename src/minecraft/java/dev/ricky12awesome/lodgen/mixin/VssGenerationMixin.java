package dev.ricky12awesome.lodgen.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Override admission before VSS captures it, leaving its configured snapshot intact. */
@Pseudo
@Mixin(targets = "dev.vox.lss.common.config.ServerConfigBase", remap = false)
public abstract class VssGenerationMixin {
    @Inject(method = "enableChunkGeneration()Z", at = @At("HEAD"), cancellable = true)
    private void lodgen$disableGeneration(CallbackInfoReturnable<Boolean> callback) {
        callback.setReturnValue(false);
    }
}
