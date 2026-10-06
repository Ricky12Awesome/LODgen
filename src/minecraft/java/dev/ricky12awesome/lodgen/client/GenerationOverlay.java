package dev.ricky12awesome.lodgen.client;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.ModSupport;
import dev.ricky12awesome.lodgen.generation.ThroughputDisplay;
import dev.ricky12awesome.lodgen.generation.GenerationProgress;
import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
import dev.ricky12awesome.lodgen.mixin.ActionBarAccess;
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
        var source = dev.ricky12awesome.lodgen.minecraft.GenerationTasks.displaySource(level);
        var meter = PersistenceRegistry.throughput(source.level());
        if (!DISPLAY.refresh(meter, LodgenConfig.INSTANCE.chunksPerSecondUpdateIntervalMs())) return;
        double rate = DISPLAY.rate();
        if (rate <= 0) { hide(client); return; }
        var progress = source.progress();
        var translated = Component.translatable("lodgen.overlay.message", String.format(Locale.ROOT, "%.1f", rate),
                progress.radius(), GenerationProgress.duration(progress.estimatedSeconds(rate)),
                Component.translatable("lodgen.overlay.state." + progress.state().name().toLowerCase(Locale.ROOT)));
        // Keep language-file formatting continuous across translation placeholders.
        lastMessage = Component.literal(translated.getString());
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
