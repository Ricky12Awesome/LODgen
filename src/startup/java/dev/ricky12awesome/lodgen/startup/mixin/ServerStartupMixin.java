package dev.ricky12awesome.lodgen.startup.mixin;

import dev.ricky12awesome.lodgen.startup.StartupCheck;

import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DedicatedServer.class)
public abstract class ServerStartupMixin {
    @Inject(method = "initServer", at = @At("HEAD"), cancellable = true)
    private void lodgen$startup(CallbackInfoReturnable<Boolean> callback) {
        try {
            StartupCheck.checkTargets();
            if (((DedicatedServer) (Object) this).getAllLevels().iterator().hasNext()) throw new AssertionError("Server loaded a world level");
            StartupCheck.report("PASS: dedicated server loaded LODgen and every installed generation mixin target; no world levels loaded.");
        } catch (Throwable failure) {
            StartupCheck.report("FAIL: " + failure);
            throw new RuntimeException("Server startup check failed", failure);
        }
        // This isolated process has no levels to save. Vanilla's normal stop path
        // assumes an overworld exists, so finish before it can open or save one.
        Runtime.getRuntime().halt(0);
    }
}
