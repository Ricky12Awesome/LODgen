package dev.lodgen.client;

import dev.lodgen.LodgenConfig;
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

/** Shared, dependency-free config screen for both loaders. Apply commits a snapshot. */
public final class LodgenConfigScreen extends Screen {
    private final Screen parent;
    private boolean enabled = LodgenConfig.INSTANCE.enabled();
    private boolean spatial = LodgenConfig.INSTANCE.spatialBatching();
    private String distance = Integer.toString(LodgenConfig.INSTANCE.generationDistance());
    private EditBox distanceInput;
    private String pipeline = Integer.toString(LodgenConfig.INSTANCE.pipelineBatches());
    private String queued = Integer.toString(LodgenConfig.INSTANCE.queuedBatches());
    private Button enabledButton;
    private Button spatialButton;
    private EditBox pipelineInput;
    private EditBox queuedInput;
    private Component error;
    private int left;
    private int top;

    public LodgenConfigScreen(Screen parent) {
        super(Component.translatable("lodgen.config.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        int rowWidth = Math.min(310, width - 24);
        left = (width - rowWidth) / 2;
        top = Math.max(40, height / 2 - 82);
        enabledButton = addRenderableWidget(Button.builder(toggle("enabled", enabled), button -> {
            enabled = !enabled;
            button.setMessage(toggle("enabled", enabled));
        }).bounds(left, top, rowWidth, 20).tooltip(tip("enabled")).build());
        pipelineInput = numberInput(left + rowWidth - 70, top + 32, "pipelineBatches", pipeline);
        pipelineInput.setResponder(value -> pipeline = value);
        queuedInput = numberInput(left + rowWidth - 70, top + 64, "queuedBatches", queued);
        queuedInput.setResponder(value -> queued = value);
        distanceInput = numberInput(left + rowWidth - 70, top + 96, "generationDistance", distance);
        distanceInput.setResponder(value -> distance = value);
        spatialButton = addRenderableWidget(Button.builder(toggle("spatialBatching", spatial), button -> {
            spatial = !spatial;
            button.setMessage(toggle("spatialBatching", spatial));
        }).bounds(left, top + 128, rowWidth, 20).tooltip(tip("spatialBatching")).build());
        int footer = height - 28;
        int buttonWidth = (rowWidth - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.defaults"), button -> reset())
                .bounds(left, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + buttonWidth + 4, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.apply"), button -> apply())
                .bounds(left + 2 * (buttonWidth + 4), footer, buttonWidth, 20).build());
    }

    private EditBox numberInput(int x, int y, String key, String value) {
        EditBox input = new EditBox(font, x, y, 70, 20, Component.translatable("lodgen.config." + key));
        input.setMaxLength(key.equals("generationDistance") ? 10 : 4);
        // Validate on Apply: 26.x Fabric no longer exposes EditBox.setFilter.
        input.setValue(value);
        input.setTooltip(tip(key));
        return addRenderableWidget(input);
    }

    private static Tooltip tip(String key) { return Tooltip.create(Component.translatable("lodgen.config." + key + ".tooltip")); }
    private static Component toggle(String key, boolean value) {
        return Component.translatable("lodgen.config." + key + ".toggle", Component.translatable(value ? "options.on" : "options.off"));
    }

    private void reset() {
        enabled = LodgenConfig.DEFAULTS.enabled();
        spatial = LodgenConfig.DEFAULTS.spatialBatching();
        enabledButton.setMessage(toggle("enabled", enabled));
        spatialButton.setMessage(toggle("spatialBatching", spatial));
        pipelineInput.setValue(Integer.toString(LodgenConfig.DEFAULTS.pipelineBatches()));
        queuedInput.setValue(Integer.toString(LodgenConfig.DEFAULTS.queuedBatches()));
        distanceInput.setValue(Integer.toString(LodgenConfig.DEFAULTS.generationDistance()));
        error = null;
    }

    private void apply() {
        try {
            LodgenConfig settings = new LodgenConfig(enabled, Integer.parseInt(pipeline), Integer.parseInt(queued), spatial, Integer.parseInt(distance));
            LodgenConfig.apply(settings);
            onClose();
        } catch (IllegalArgumentException invalid) {
            error = Component.translatable("lodgen.config.invalid");
        } catch (IOException | RuntimeException failure) {
            LodgenConfig.LOGGER.error("Cannot save LODgen settings", failure);
            error = Component.translatable("lodgen.config.saveFailed");
        }
    }

    // #if MC_1211
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xffffffff);
        graphics.drawString(font, Component.translatable("lodgen.config.pipelineBatches"), left, top + 38, 0xffffffff);
        graphics.drawString(font, Component.translatable("lodgen.config.queuedBatches"), left, top + 70, 0xffffffff);
        graphics.drawString(font, Component.translatable("lodgen.config.generationDistance"), left, top + 102, 0xffffffff);
        graphics.drawCenteredString(font, error == null ? Component.translatable("lodgen.config.live") : error,
                width / 2, height - 46, error == null ? 0xffaaaaaa : 0xffff6666);
    }
    // #else
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Screen.extractRenderStateWithTooltipAndSubtitles already extracts the background.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 16, 0xffffffff);
        graphics.text(font, Component.translatable("lodgen.config.pipelineBatches"), left, top + 38, 0xffffffff);
        graphics.text(font, Component.translatable("lodgen.config.queuedBatches"), left, top + 70, 0xffffffff);
        graphics.text(font, Component.translatable("lodgen.config.generationDistance"), left, top + 102, 0xffffffff);
        graphics.centeredText(font, error == null ? Component.translatable("lodgen.config.live") : error,
                width / 2, height - 46, error == null ? 0xffaaaaaa : 0xffff6666);
    }
    // #endif

    @Override public void onClose() { ClientScreens.open(minecraft, parent); }
}
