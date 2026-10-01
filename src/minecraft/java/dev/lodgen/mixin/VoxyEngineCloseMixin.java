package dev.lodgen.mixin;

import dev.lodgen.voxy.VoxyGeneration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.WorldEngine", remap = false)
public abstract class VoxyEngineCloseMixin {
    @Inject(method = "free", at = @At("RETURN"))
    private void lodgen$checkpoint(CallbackInfo ci) { VoxyGeneration.engineClosed(this); }
}
