package dev.lodgen.startup;

import dev.lodgen.LodgenConfig;
import dev.lodgen.ModSupport;
import dev.lodgen.client.GenerationOverlay;

/** Check live HUD priority without creating a world or persisting test settings. */
public final class OverlayCheck {
    public static boolean rendered;
    private OverlayCheck() {}
    public static void checkMessageOwnership(net.minecraft.client.Minecraft client) {
        var probe = net.minecraft.network.chat.Component.literal("Another mod's action bar");
        // #if MC_1211
        client.player.displayClientMessage(probe, true);
        // #else
        client.player.sendOverlayMessage(probe);
        // #endif
        var original = LodgenConfig.INSTANCE;
        try {
            LodgenConfig.INSTANCE = new LodgenConfig(original.enabled(), original.cpuLoad(), original.generationDistance(), false, original.chunksPerSecondUpdateIntervalMs(), original.generationCenter(), original.centerX(), original.centerZ(), original.savedChunkRadius());
            GenerationOverlay.tick(client);
            // #if MC_262_PLUS
            var actionBar = (dev.lodgen.mixin.ActionBarAccess) client.gui.hud;
            // #else
            var actionBar = (dev.lodgen.mixin.ActionBarAccess) client.gui;
            // #endif
            if (!probe.equals(actionBar.lodgen$actionBarMessage())) throw new AssertionError("LODgen erased another action-bar message");
        } finally { LodgenConfig.INSTANCE = original; }
        GenerationOverlay.tick(client);
    }

    public static void check() throws Exception {
        var original = LodgenConfig.INSTANCE;
        Object entry = null;
        Object oldLocation = null;
        java.lang.reflect.Method set = null;
        try {
            if (ModSupport.loaded("distanthorizons")) {
                entry = Class.forName("com.seibel.distanthorizons.core.config.Config$Common$WorldGenerator")
                        .getField("showGenerationProgress").get(null);
                oldLocation = entry.getClass().getMethod("get").invoke(entry);
                set = entry.getClass().getMethod("set", Object.class);
            }
            for (boolean enabled : new boolean[]{false, true}) {
                LodgenConfig.INSTANCE = new LodgenConfig(original.enabled(), original.cpuLoad(), original.generationDistance(), enabled, original.chunksPerSecondUpdateIntervalMs(), original.generationCenter(), original.centerX(), original.centerZ(), original.savedChunkRadius());
                if (entry == null) {
                    if (GenerationOverlay.visible() != enabled) throw new AssertionError("Voxy-only overlay toggle ignored");
                } else {
                    for (Object location : oldLocation.getClass().getEnumConstants()) {
                        set.invoke(entry, location);
                        boolean expected = enabled && !((Enum<?>) location).name().equals("OVERLAY");
                        if (GenerationOverlay.visible() != expected) throw new AssertionError("DH overlay priority incorrect: " + location);
                    }
                }
            }
        } finally {
            LodgenConfig.INSTANCE = original;
            if (entry != null && set != null) set.invoke(entry, oldLocation);
        }
    }
}
