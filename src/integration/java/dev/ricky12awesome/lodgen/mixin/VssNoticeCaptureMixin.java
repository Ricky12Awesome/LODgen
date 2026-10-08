package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.integration.VssCheck;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures only the integration probe's outbound readiness packet. */
@Pseudo
@Mixin(targets = "dev.vox.lss.platform.NeoForgeLoaderServices", remap = false)
public abstract class VssNoticeCaptureMixin {
    @Inject(method = "sendToPlayer", at = @At("HEAD"), cancellable = true, remap = false)
    private void lodgen$captureVssNotice(ServerPlayer player, CustomPacketPayload payload, CallbackInfo callback) {
        if (VssCheck.captureNotice(player, payload)) callback.cancel();
    }
}
