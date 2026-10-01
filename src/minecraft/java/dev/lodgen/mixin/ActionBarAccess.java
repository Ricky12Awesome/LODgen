package dev.lodgen.mixin;

// #if MC_262_PLUS
import net.minecraft.client.gui.Hud;
// #else
import net.minecraft.client.gui.Gui;
// #endif
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// #if MC_262_PLUS
@Mixin(Hud.class)
// #else
@Mixin(Gui.class)
// #endif
public interface ActionBarAccess {
    @Accessor("overlayMessageString") Component lodgen$actionBarMessage();
}
