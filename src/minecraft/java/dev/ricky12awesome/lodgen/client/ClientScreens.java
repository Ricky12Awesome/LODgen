package dev.ricky12awesome.lodgen.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** The screen navigation API moved in Minecraft 26.2. */
public final class ClientScreens {
    private ClientScreens() {}

    public static void open(Minecraft minecraft, Screen screen) {
        // #if MC_262_PLUS
        minecraft.gui.setScreen(screen);
        // #else
        minecraft.setScreen(screen);
        // #endif
    }

    public static Screen current(Minecraft minecraft) {
        // #if MC_262_PLUS
        return minecraft.gui.screen();
        // #else
        return minecraft.screen;
        // #endif
    }
}
