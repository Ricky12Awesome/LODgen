package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VssGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MCA timestamps cannot prove that independently stored LOD terrain is current. */
@Pseudo
@Mixin(targets = "dev.vox.lss.common.region.RegionStampTable", remap = false)
public abstract class VssRegionMixin {
    @Inject(method = "tileStampSeconds", at = @At("HEAD"), cancellable = true)
    private void lodgen$tile(String dimension, int x, int z, CallbackInfoReturnable<Long> callback) {
        if (VssGeneration.generatedRegion(this, dimension, x, z)) callback.setReturnValue(Long.MAX_VALUE);
    }

    @Inject(method = "chunkStampSecondsOrUnknown", at = @At("HEAD"), cancellable = true)
    private void lodgen$column(String dimension, int x, int z, CallbackInfoReturnable<Long> callback) {
        if (VssGeneration.generatedRegion(this, dimension, x >> 5, z >> 5)) callback.setReturnValue(Long.MAX_VALUE);
    }

    @Inject(method = "isClaimSuppressed", at = @At("HEAD"), cancellable = true)
    private void lodgen$stamp(String dimension, int x, int z, CallbackInfoReturnable<Boolean> callback) {
        if (VssGeneration.generatedRegion(this, dimension, x >> 5, z >> 5)) callback.setReturnValue(true);
    }
}
