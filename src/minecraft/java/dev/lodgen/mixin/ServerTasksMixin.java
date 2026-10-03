package dev.lodgen.mixin;

import dev.lodgen.minecraft.GenerationTasks;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class ServerTasksMixin {
    @Inject(method = "pollTaskInternal", at = @At("RETURN"), cancellable = true)
    private void lodgen$poll(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() && dev.lodgen.minecraft.LodGenerationWorld.pollTask((MinecraftServer) (Object) this))
            callback.setReturnValue(true);
    }
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void lodgen$tasks(CallbackInfo callback) {
        dev.lodgen.minecraft.LodGenerationWorld.tick((MinecraftServer) (Object) this);
        GenerationTasks.tick((MinecraftServer) (Object) this);
    }
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void lodgen$stopTasks(CallbackInfo callback) {
        GenerationTasks.beginShutdown((MinecraftServer) (Object) this);
        dev.lodgen.minecraft.LodGenerationWorld.close((MinecraftServer) (Object) this);
    }
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void lodgen$checkpoint(CallbackInfo callback) { GenerationTasks.endShutdown((MinecraftServer) (Object) this); }
}
