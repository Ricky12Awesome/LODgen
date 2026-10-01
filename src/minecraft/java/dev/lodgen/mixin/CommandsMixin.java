package dev.lodgen.mixin;

import dev.lodgen.minecraft.LodgenCommands;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodgen$commands(CallbackInfo callback) {
        LodgenCommands.register(((Commands) (Object) this).getDispatcher());
    }
}
