package dev.ricky12awesome.lodgen.mixin;

import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Keep legacy formatting active across the fragments created by translation placeholders. */
@Mixin(TranslatableContents.class)
public abstract class CommandFormattingMixin {
    @Shadow public abstract String getKey();
    @Shadow public abstract <T> Optional<T> visit(FormattedText.ContentConsumer<T> consumer);

    @Inject(method = "visit(Lnet/minecraft/network/chat/FormattedText$StyledContentConsumer;Lnet/minecraft/network/chat/Style;)Ljava/util/Optional;",
            at = @At("HEAD"), cancellable = true)
    protected <T> void lodgen$commandFormatting(FormattedText.StyledContentConsumer<T> consumer, Style style,
                                              CallbackInfoReturnable<Optional<T>> callback) {
        if (!getKey().startsWith("lodgen.command.")) return;
        StringBuilder text = new StringBuilder();
        visit(fragment -> {
            text.append(fragment);
            return Optional.empty();
        });
        callback.setReturnValue(consumer.accept(style, text.toString()));
    }
}
