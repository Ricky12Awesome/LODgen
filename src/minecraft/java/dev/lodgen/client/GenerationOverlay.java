package dev.lodgen.client;

import dev.lodgen.LodgenConfig;
import dev.lodgen.ModSupport;
import dev.lodgen.generation.ThroughputDisplay;
import dev.lodgen.minecraft.PersistenceRegistry;
import dev.lodgen.mixin.ActionBarAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Uses the same vanilla action-bar message path as DH's generation overlay. */
public final class GenerationOverlay {
    private static final ThroughputDisplay DISPLAY = new ThroughputDisplay();
    private static Component lastMessage;
    private GenerationOverlay() {}

    public static boolean visible() {
        return LodgenConfig.INSTANCE.showChunksPerSecond()
                && !(ModSupport.loaded("distanthorizons") && DhOverlaySettings.usesOverlay());
    }

    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null || !visible()) {
            DISPLAY.clear();
            hide(client);
            return;
        }
        var server = client.getSingleplayerServer();
        var level = server == null ? null : server.getLevel(client.level.dimension());
        if (level == null) { DISPLAY.clear(); hide(client); return; }
        var meter = PersistenceRegistry.throughput(level);
        if (!DISPLAY.refresh(meter, LodgenConfig.INSTANCE.chunksPerSecondUpdateIntervalMs())) return;
        double rate = DISPLAY.rate();
        if (rate <= 0) { hide(client); return; }
        lastMessage = Component.translatable("lodgen.overlay.throughput", String.format(Locale.ROOT, "%.1f", rate));
        send(client, lastMessage);
    }

    private static void hide(Minecraft client) {
        if (lastMessage != null && client.player != null) {
            // Do not erase an action-bar message that replaced ours in the meantime.
            // #if MC_262_PLUS
            var actionBar = (ActionBarAccess) client.gui.hud;
            // #else
            var actionBar = (ActionBarAccess) client.gui;
            // #endif
            if (lastMessage.equals(actionBar.lodgen$actionBarMessage())) send(client, Component.empty());
        }
        lastMessage = null;
    }

    private static void send(Minecraft client, Component message) {
        // #if MC_1211
        client.player.displayClientMessage(message, true);
        // #else
        client.player.sendOverlayMessage(message);
        // #endif
    }
}
