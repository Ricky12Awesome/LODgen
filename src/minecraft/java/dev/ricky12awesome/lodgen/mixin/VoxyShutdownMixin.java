package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VoxyGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.VoxyCommon", remap = false)
public abstract class VoxyShutdownMixin {
    @Inject(method = "shutdownInstance", at = @At("HEAD"))
    private static void lodgen$stopGeneration(CallbackInfo ci) { VoxyGeneration.beforeShutdown(); }
}
