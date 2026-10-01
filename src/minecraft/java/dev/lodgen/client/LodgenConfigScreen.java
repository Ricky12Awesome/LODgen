package dev.lodgen.client;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationCenter;
// #if MC_1211
import net.minecraft.client.gui.GuiGraphics;
// #else
import net.minecraft.client.gui.GuiGraphicsExtractor;
// #endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Two compact pages share one draft; Apply atomically commits all settings. */
public final class LodgenConfigScreen extends Screen {
    private final Screen parent;
    private boolean enabled = LodgenConfig.INSTANCE.enabled();
    private boolean spatial = LodgenConfig.INSTANCE.spatialBatching();
    private boolean throughput = LodgenConfig.INSTANCE.showChunksPerSecond();
    private GenerationCenter center = LodgenConfig.INSTANCE.generationCenter();
    private final Map<String, String> numbers = new HashMap<>();
    private final Map<String, EditBox> visibleInputs = new HashMap<>();
    private boolean advanced;
    private Component error;
    private int left, top, rowWidth;

    public LodgenConfigScreen(Screen parent) {
        super(Component.translatable("lodgen.config.title"));
        this.parent = parent;
        setNumbers(LodgenConfig.INSTANCE);
    }
    private void setNumbers(LodgenConfig values) {
        numbers.put("pipelineBatches", Integer.toString(values.pipelineBatches()));
        numbers.put("queuedBatches", Integer.toString(values.queuedBatches()));
        numbers.put("generationDistance", Integer.toString(values.generationDistance()));
        numbers.put("savedChunkRadius", Integer.toString(values.savedChunkRadius()));
        numbers.put("centerX", Integer.toString(values.centerX()));
        numbers.put("centerZ", Integer.toString(values.centerZ()));
        numbers.put("chunksPerSecondUpdateIntervalMs", Integer.toString(values.chunksPerSecondUpdateIntervalMs()));
    }
    @Override protected void init() {
        rowWidth = Math.min(310, width - 24);
        left = (width - rowWidth) / 2;
        top = Math.max(58, height / 2 - 62);
        visibleInputs.clear();
        addRenderableWidget(Button.builder(Component.translatable(advanced ? "lodgen.config.generationPage" : "lodgen.config.advancedPage"), button -> {
            advanced = !advanced; rebuildWidgets();
        }).bounds(left, 32, rowWidth, 20).build());
        if (advanced) {
            numberInput("pipelineBatches", left + rowWidth - 80, top, 80);
            numberInput("queuedBatches", left + rowWidth - 80, top + 24, 80);
            toggleButton("spatialBatching", spatial, top + 48, button -> { spatial = !spatial; button.setMessage(toggle("spatialBatching", spatial)); });
            toggleButton("showChunksPerSecond", throughput, top + 72, button -> { throughput = !throughput; button.setMessage(toggle("showChunksPerSecond", throughput)); });
            numberInput("chunksPerSecondUpdateIntervalMs", left + rowWidth - 80, top + 96, 80);
        } else {
            toggleButton("enabled", enabled, top, button -> { enabled = !enabled; button.setMessage(toggle("enabled", enabled)); });
            addRenderableWidget(Button.builder(centerLabel(), button -> {
                center = center.next(); button.setMessage(centerLabel());
                visibleInputs.get("centerX").active = visibleInputs.get("centerZ").active = center == GenerationCenter.CUSTOM;
            }).bounds(left, top + 24, rowWidth, 20).tooltip(tip("generationCenter")).build());
            numberInput("centerX", left + 24, top + 48, rowWidth / 2 - 28).active = center == GenerationCenter.CUSTOM;
            numberInput("centerZ", left + rowWidth / 2 + 24, top + 48, rowWidth / 2 - 24).active = center == GenerationCenter.CUSTOM;
            numberInput("generationDistance", left + rowWidth - 80, top + 72, 80);
            numberInput("savedChunkRadius", left + rowWidth - 80, top + 96, 80);
        }
        int footer = height - 28, buttonWidth = (rowWidth - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.defaults"), button -> reset()).bounds(left, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose()).bounds(left + buttonWidth + 4, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.apply"), button -> apply()).bounds(left + 2 * (buttonWidth + 4), footer, buttonWidth, 20).build());
    }
    private void toggleButton(String key, boolean value, int y, Button.OnPress action) {
        addRenderableWidget(Button.builder(toggle(key, value), action).bounds(left, y, rowWidth, 20).tooltip(tip(key)).build());
    }
    private EditBox numberInput(String key, int x, int y, int width) {
        var input = new EditBox(font, x, y, width, 20, Component.translatable("lodgen.config." + key));
        input.setMaxLength(11); input.setValue(numbers.get(key)); input.setTooltip(tip(key));
        input.setResponder(value -> numbers.put(key, value));
        visibleInputs.put(key, input);
        return addRenderableWidget(input);
    }
    private Component centerLabel() {
        return Component.translatable("lodgen.config.generationCenter", Component.translatable("lodgen.config.center." + center.name().toLowerCase(java.util.Locale.ROOT)));
    }
    private static Tooltip tip(String key) { return Tooltip.create(Component.translatable("lodgen.config." + key + ".tooltip")); }
    private static Component toggle(String key, boolean value) {
        return Component.translatable("lodgen.config." + key + ".toggle", Component.translatable(value ? "options.on" : "options.off"));
    }
    private int number(String key) { return Integer.parseInt(numbers.get(key)); }
    private void reset() {
        var defaults = LodgenConfig.DEFAULTS;
        enabled = defaults.enabled(); spatial = defaults.spatialBatching(); throughput = defaults.showChunksPerSecond(); center = defaults.generationCenter();
        setNumbers(defaults); error = null; rebuildWidgets();
    }
    private void apply() {
        try {
            LodgenConfig.apply(new LodgenConfig(enabled, number("pipelineBatches"), number("queuedBatches"), spatial,
                    number("generationDistance"), throughput, number("chunksPerSecondUpdateIntervalMs"), center, number("centerX"), number("centerZ"), number("savedChunkRadius")));
            onClose();
        } catch (IllegalArgumentException invalid) { error = Component.translatable("lodgen.config.invalid"); }
        catch (IOException | RuntimeException failure) {
            LodgenConfig.LOGGER.error("Cannot save LODgen settings", failure);
            error = Component.translatable("lodgen.config.saveFailed");
        }
    }
    // #if MC_1211
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xffffffff);
        if (advanced) {
            graphics.drawString(font, Component.translatable("lodgen.config.pipelineBatches"), left, top + 6, 0xffffffff);
            graphics.drawString(font, Component.translatable("lodgen.config.queuedBatches"), left, top + 30, 0xffffffff);
            graphics.drawString(font, Component.translatable("lodgen.config.chunksPerSecondUpdateIntervalMs"), left, top + 102, 0xffffffff);
        } else {
            graphics.drawString(font, "X", left, top + 54, 0xffffffff);
            graphics.drawString(font, "Z", left + rowWidth / 2, top + 54, 0xffffffff);
            graphics.drawString(font, Component.translatable("lodgen.config.generationDistance"), left, top + 78, 0xffffffff);
            graphics.drawString(font, Component.translatable("lodgen.config.savedChunkRadius"), left, top + 102, 0xffffffff);
        }
        graphics.drawCenteredString(font, error == null ? Component.translatable("lodgen.config.live") : error, width / 2, height - 46, error == null ? 0xffaaaaaa : 0xffff6666);
    }
    // #else
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 16, 0xffffffff);
        if (advanced) {
            graphics.text(font, Component.translatable("lodgen.config.pipelineBatches"), left, top + 6, 0xffffffff);
            graphics.text(font, Component.translatable("lodgen.config.queuedBatches"), left, top + 30, 0xffffffff);
            graphics.text(font, Component.translatable("lodgen.config.chunksPerSecondUpdateIntervalMs"), left, top + 102, 0xffffffff);
        } else {
            graphics.text(font, "X", left, top + 54, 0xffffffff);
            graphics.text(font, "Z", left + rowWidth / 2, top + 54, 0xffffffff);
            graphics.text(font, Component.translatable("lodgen.config.generationDistance"), left, top + 78, 0xffffffff);
            graphics.text(font, Component.translatable("lodgen.config.savedChunkRadius"), left, top + 102, 0xffffffff);
        }
        graphics.centeredText(font, error == null ? Component.translatable("lodgen.config.live") : error, width / 2, height - 46, error == null ? 0xffaaaaaa : 0xffff6666);
    }
    // #endif
    @Override public void onClose() { ClientScreens.open(minecraft, parent); }
}
