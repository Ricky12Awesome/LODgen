package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VoxyGeneration;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class VoxyClientTickMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void lodgen$voxyTick(CallbackInfo ci) { VoxyGeneration.tick((Minecraft) (Object) this); }
}
