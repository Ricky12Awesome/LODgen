package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.client.LodgenConfigScreen;
import dev.ricky12awesome.lodgen.client.ClientScreens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Options remains available on Fabric without installing a config menu mod. */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin extends Screen {
    protected OptionsScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void lodgen$configButton(CallbackInfo callback) {
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.screen.button.open"), button -> ClientScreens.open(minecraft, new LodgenConfigScreen(this)))
                .bounds(width - 104, 4, 100, 20).build());
    }
}
