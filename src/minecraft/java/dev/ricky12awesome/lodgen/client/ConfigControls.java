package dev.ricky12awesome.lodgen.client;

import dev.ricky12awesome.lodgen.config.ConfigDraft;
import dev.ricky12awesome.lodgen.config.ConfigSchema;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Minecraft widgets for any shared configuration schema. */
final class ConfigControls {
    private final Font font;
    private final ConfigDraft<?> draft;
    private final int width;
    private final Predicate<String> modLoaded;
    private final Map<ConfigSchema.Option, AbstractWidget> widgets = new LinkedHashMap<>();

    ConfigControls(Font font, ConfigDraft<?> draft, int width, Predicate<String> modLoaded) {
        this.font = font;
        this.draft = draft;
        this.width = width;
        this.modLoaded = modLoaded;
    }

    AbstractWidget create(ConfigSchema.Option option) {
        var label = Component.translatable(option.labelKey());
        var tooltip = Tooltip.create(Component.translatable(option.tooltipKey()));
        AbstractWidget widget;
        if (option.control() == ConfigSchema.Control.NUMBER) {
            var input = new EditBox(font, 0, 0, width, 20, label);
            input.setMaxLength(11); // Enough for every signed 32-bit integer.
            input.setValue(draft.text(option));
            input.setTooltip(tooltip);
            input.setResponder(text -> { draft.edit(option, text); refresh(); });
            widget = input;
        } else {
            widget = Button.builder(value(option), button -> {
                draft.cycle(option);
                if (option.key().equals("generationCenter") && draft.text(option).equalsIgnoreCase("current")) draft.cycle(option);
                refresh();
            })
                    .bounds(0, 0, width, 20).tooltip(tooltip)
                    .createNarration(ignored -> Component.translatable("lodgen.config.narration", label, value(option))).build();
        }
        widgets.put(option, widget);
        refresh();
        return widget;
    }

    private Component value(ConfigSchema.Option option) {
        return Component.translatable(draft.valueKey(option, modLoaded));
    }

    private void refresh() {
        widgets.forEach((option, widget) -> {
            widget.active = draft.active(option, modLoaded);
            if (widget instanceof EditBox input) input.setEditable(widget.active);
            if (option.key().equals("centerX") || option.key().equals("centerZ")) {
                widget.visible = true;
                if (!widget.active) widget.setFocused(false);
            }
            if (widget instanceof Button button) button.setMessage(value(option));
        });
    }
}
