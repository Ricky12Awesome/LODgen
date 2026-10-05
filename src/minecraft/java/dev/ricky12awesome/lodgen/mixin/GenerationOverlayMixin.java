package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.client.GenerationOverlay;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class GenerationOverlayMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void lodgen$throughput(CallbackInfo callback) {
        GenerationOverlay.tick((Minecraft) (Object) this);
    }
}
