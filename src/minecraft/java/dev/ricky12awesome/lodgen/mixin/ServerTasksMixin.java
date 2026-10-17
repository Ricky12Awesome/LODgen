package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.GenerationSettings;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class ServerTasksMixin {
    @Unique private long lodgen$tickStarted;
    @Unique private long lodgen$tickInterval;

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void lodgen$startTick(CallbackInfo callback) {
        long now = System.nanoTime();
        lodgen$tickInterval = lodgen$tickStarted == 0 ? 0 : now - lodgen$tickStarted;
        lodgen$tickStarted = now;
    }
    @Inject(method = "pollTaskInternal", at = @At("RETURN"), cancellable = true)
    private void lodgen$poll(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() && dev.ricky12awesome.lodgen.minecraft.LodGenerationWorld.pollTask((MinecraftServer) (Object) this))
            callback.setReturnValue(true);
    }
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void lodgen$tasks(CallbackInfo callback) {
        // Include work between ticks: main-thread chunk tasks can delay the next
        // tick even when tickServer itself completes in less than 50ms.
        GenerationSettings.serverTick(this, Math.max(lodgen$tickInterval, System.nanoTime() - lodgen$tickStarted));
        dev.ricky12awesome.lodgen.minecraft.LodGenerationWorld.tick((MinecraftServer) (Object) this);
        if (dev.ricky12awesome.lodgen.ModSupport.loaded("lss"))
            dev.ricky12awesome.lodgen.voxy.VssGeneration.tick((MinecraftServer) (Object) this);
        GenerationTasks.tick((MinecraftServer) (Object) this);
        dev.ricky12awesome.lodgen.minecraft.ServerOperatorOverlay.tick((MinecraftServer) (Object) this);
    }
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void lodgen$stopTasks(CallbackInfo callback) {
        GenerationTasks.beginShutdown((MinecraftServer) (Object) this);
        if (dev.ricky12awesome.lodgen.ModSupport.loaded("lss"))
            dev.ricky12awesome.lodgen.voxy.VssGeneration.serverStopping((MinecraftServer) (Object) this);
        dev.ricky12awesome.lodgen.minecraft.ServerOperatorOverlay.remove((MinecraftServer) (Object) this);
        dev.ricky12awesome.lodgen.minecraft.LodGenerationWorld.close((MinecraftServer) (Object) this);
    }
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void lodgen$checkpoint(CallbackInfo callback) {
        GenerationTasks.endShutdown((MinecraftServer) (Object) this);
        GenerationSettings.serverStopped(this);
    }
}
