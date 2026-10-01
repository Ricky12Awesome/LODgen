package dev.dhc2me.mixin;

import dev.dhc2me.minecraft.PersistenceRegistry;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class LevelCloseMixin {
    @Inject(method = "close", at = @At("RETURN"))
    private void dhc2me$clear(CallbackInfo ci) { PersistenceRegistry.close((ServerLevel) (Object) this); }
}
