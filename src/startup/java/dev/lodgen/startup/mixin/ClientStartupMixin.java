package dev.lodgen.startup.mixin;

import dev.lodgen.startup.StartupCheck;
import dev.lodgen.startup.VoxyWorldCheck;

import dev.lodgen.client.ClientScreens;
import dev.lodgen.client.LodgenConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
// #if MC_26
import net.minecraft.client.input.KeyEvent;
// #endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientStartupMixin {
    @Unique private int lodgen$stage;
    @Unique private int lodgen$ticks;
    @Unique private OptionsScreen lodgen$options;
    @Unique private Screen lodgen$configParent;

    @Inject(method = "tick", at = @At("TAIL"))
    private void lodgen$startup(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (!minecraft.isGameLoadFinished()) return;
        if (lodgen$stage == 3) {
            if (Integer.getInteger("lodgen.test.automaticSeconds", 0) > 0) dev.lodgen.startup.VoxyAutomaticCheck.tick(minecraft);
            else if (Integer.getInteger("lodgen.test.voxyBenchmark", 0) > 0) dev.lodgen.startup.VoxyBenchmark.tick(minecraft);
            else if (Boolean.getBoolean("lodgen.test.voxyWorld")) VoxyWorldCheck.tick(minecraft);
            return;
        }
        // #if MC_262_PLUS
        if (minecraft.gui.overlay() != null) return;
        // #else
        if (minecraft.getOverlay() != null) return;
        // #endif
        try {
            if (minecraft.level != null) throw new AssertionError("Startup check opened a world");
            if (lodgen$stage == 0) {
                StartupCheck.checkTargets();
                dev.lodgen.startup.OverlayCheck.check();
                // #if MC_262_PLUS
                Class.forName(net.minecraft.client.gui.Hud.class.getName());
                // #else
                Class.forName(net.minecraft.client.gui.Gui.class.getName());
                // #endif
                if (Boolean.getBoolean("lodgen.test.voxy")) {
                    new dev.lodgen.voxy.VoxyBridge();
                    Class.forName(net.minecraft.client.server.IntegratedServer.class.getName());
                }
                ClientScreens.open(minecraft, new TitleScreen());
                lodgen$stage = 1;
                return;
            }
            if (++lodgen$ticks < 5) return;
            lodgen$ticks = 0;
            if (lodgen$stage == 1) {
                // #if MC_263
                lodgen$options = new OptionsScreen(ClientScreens.current(minecraft), minecraft.options);
                // #else
                // #if MC_26
                lodgen$options = new OptionsScreen(ClientScreens.current(minecraft), minecraft.options, false);
                // #else
                lodgen$options = new OptionsScreen(ClientScreens.current(minecraft), minecraft.options);
                // #endif
                // #endif
                ClientScreens.open(minecraft, lodgen$options);
                Button button = lodgen$options.children().stream().filter(child -> child instanceof Button)
                        .map(child -> (Button) child).filter(child -> child.getMessage().getString().equals("LODgen…"))
                        .findFirst().orElseThrow(() -> new AssertionError("Options has no LODgen button"));
                // #if MC_1211
                button.onPress();
                // #else
                button.onPress(new KeyEvent(257, 0, 0));
                // #endif
                if (!(ClientScreens.current(minecraft) instanceof LodgenConfigScreen)) throw new AssertionError("Config button did not open screen");
                lodgen$configParent = lodgen$options;
                if (Boolean.getBoolean("lodgen.test.modmenu")) {
                    Screen menu = (Screen) Class.forName("com.terraformersmc.modmenu.api.ModMenuApi")
                            .getMethod("createModsScreen", Screen.class).invoke(null, lodgen$options);
                    ClientScreens.open(minecraft, menu);
                    Screen config = (Screen) Class.forName("com.terraformersmc.modmenu.ModMenu")
                            .getMethod("getConfigScreen", String.class, Screen.class).invoke(null, "lodgen", menu);
                    if (!(config instanceof LodgenConfigScreen)) throw new AssertionError("Mod Menu did not register LODgen's config factory");
                    ClientScreens.open(minecraft, config);
                    lodgen$configParent = menu;
                }
                lodgen$stage = 2;
                return;
            }
            Screen config = ClientScreens.current(minecraft);
            if (!(config instanceof LodgenConfigScreen)) throw new AssertionError("Config screen closed unexpectedly");
            if (lodgen$stage == 2) {
                dev.lodgen.startup.ConfigScreenCheck.begin(minecraft, config);
                lodgen$stage = 4;
                return;
            }
            dev.lodgen.startup.ConfigScreenCheck.finish(minecraft, config);
            config.onClose();
            if (ClientScreens.current(minecraft) != lodgen$configParent) throw new AssertionError("Config did not return to its parent");
            StartupCheck.report("PASS: client title, Options button and single-page LODgen config rendered; all 9 settings, scrolling, input focus and drafts across resizing verified; "
                    + (Boolean.getBoolean("lodgen.test.modmenu") ? "Mod Menu factory opened config and returned to Mods; " : "")
                    + "generation mixin targets loaded; no world opened.");
            lodgen$stage = 3;
            if (Integer.getInteger("lodgen.test.automaticSeconds", 0) > 0 || Integer.getInteger("lodgen.test.voxyBenchmark", 0) > 0) dev.lodgen.startup.VoxyBenchmark.start(minecraft);
            else if (Boolean.getBoolean("lodgen.test.voxyWorld")) VoxyWorldCheck.start(minecraft);
            else minecraft.stop();
        } catch (Throwable failure) {
            StartupCheck.report("FAIL: " + failure);
            throw new RuntimeException("Client startup check failed", failure);
        }
    }
}
