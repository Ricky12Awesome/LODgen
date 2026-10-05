package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.voxy.VoxyGeneration;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class VoxyServerShutdownMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void lodgen$stopGeneration(CallbackInfo callback) {
        dev.ricky12awesome.lodgen.minecraft.GenerationTasks.beginShutdown((MinecraftServer) (Object) this);
        VoxyGeneration.serverStopping((MinecraftServer) (Object) this);
    }
}
