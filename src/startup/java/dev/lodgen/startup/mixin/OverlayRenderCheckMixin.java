package dev.lodgen.startup.mixin;

import dev.lodgen.client.GenerationOverlay;
import dev.lodgen.mixin.ActionBarAccess;
import dev.lodgen.startup.OverlayCheck;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the actual vanilla action-bar text in a Voxy-only world. */
@Mixin(GenerationOverlay.class)
public abstract class OverlayRenderCheckMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private static void lodgen$rendered(Minecraft client, CallbackInfo callback) {
        if (client.level == null || client.player == null || !GenerationOverlay.visible()) return;
        // #if MC_262_PLUS
        var access = (ActionBarAccess) client.gui.hud;
        // #else
        var access = (ActionBarAccess) client.gui;
        // #endif
        var message = access.lodgen$actionBarMessage();
        if (message != null && message.getString().startsWith("LODgen:")) {
            String text = message.getString();
            if (!text.contains("chunks/s | Radius: ") || !text.contains("c | ETA: "))
                throw new AssertionError("Vanilla action bar omitted generation details: " + text);
            OverlayCheck.rendered = true;
        }
    }
}
