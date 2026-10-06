package dev.ricky12awesome.lodgen.client;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.ModSupport;
import dev.ricky12awesome.lodgen.config.ConfigDraft;
import dev.ricky12awesome.lodgen.config.ConfigSchema;
// #if MC_1211
import net.minecraft.client.gui.GuiGraphics;
// #else
import net.minecraft.client.gui.GuiGraphicsExtractor;
// #endif
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** All settings share one scrollable list and one draft. */
public final class LodgenConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private final Screen parent;
    private final ConfigDraft<LodgenConfig> draft;
    private SettingsList settings;
    private Component error;
    private int rowWidth, controlWidth;

    public LodgenConfigScreen(Screen parent) {
        super(Component.translatable("lodgen.config.title"));
        this.parent = parent;
        draft = LodgenConfig.SCHEMA.draft(LodgenConfig.INSTANCE);
    }
    @Override protected void init() {
        double scroll = settings == null ? 0 : settings.position();
        rowWidth = Math.min(440, width - 40);
        controlWidth = Math.min(200, rowWidth / 2);
        settings = addRenderableWidget(new SettingsList());
        var controls = new ConfigControls(font, draft, controlWidth, ModSupport::loaded);
        for (var option : LodgenConfig.SCHEMA.options()) settings.add(option, controls.create(option));
        settings.setScrollAmount(scroll);

        int left = (width - rowWidth) / 2, footer = height - 28, buttonWidth = (rowWidth - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.defaults"), button -> reset()).bounds(left, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose()).bounds(left + buttonWidth + 4, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.apply"), button -> apply()).bounds(left + 2 * (buttonWidth + 4), footer, buttonWidth, 20).build());
    }
    private void reset() {
        draft.reset(LodgenConfig.DEFAULTS);
        error = null;
        rebuildWidgets();
    }
    private void apply() {
        try {
            LodgenConfig.apply(draft.snapshot());
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
        graphics.drawCenteredString(font, Component.translatable("lodgen.config.units"), width / 2, 30, 0xffaaaaaa);
        graphics.drawCenteredString(font, error == null ? Component.translatable("lodgen.config.live") : error, width / 2, height - 46, error == null ? 0xffaaaaaa : 0xffff6666);
    }
    // #else
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 16, 0xffffffff);
        graphics.centeredText(font, Component.translatable("lodgen.config.units"), width / 2, 30, 0xffaaaaaa);
        graphics.centeredText(font, error == null ? Component.translatable("lodgen.config.live") : error, width / 2, height - 46, error == null ? 0xffaaaaaa : 0xffff6666);
    }
    // #endif
    @Override public void onClose() { ClientScreens.open(minecraft, parent); }

    private final class SettingsList extends ContainerObjectSelectionList<SettingRow> {
        SettingsList() {
            super(LodgenConfigScreen.this.minecraft, LodgenConfigScreen.this.width, Math.max(28, LodgenConfigScreen.this.height - 100), 44, ROW_HEIGHT);
            centerListVertically = false;
        }
        void add(ConfigSchema.Option option, AbstractWidget widget) { addEntry(new SettingRow(option, widget)); }
        double position() {
            // #if MC_1211
            return getScrollAmount();
            // #else
            return scrollAmount();
            // #endif
        }
        @Override public int getRowWidth() { return rowWidth; }
        // #if MC_1211
        @Override protected int getScrollbarPosition() { return getRowRight() + 8; }
        // #else
        @Override protected int scrollBarX() { return getRowRight() + 8; }
        // #endif
    }
    private final class SettingRow extends ContainerObjectSelectionList.Entry<SettingRow> {
        private final Component name;
        private final AbstractWidget widget;
        SettingRow(ConfigSchema.Option option, AbstractWidget widget) {
            name = Component.translatable(option.labelKey());
            this.widget = widget;
        }
        @Override public List<? extends GuiEventListener> children() { return List.of(widget); }
        @Override public List<? extends NarratableEntry> narratables() { return List.of(widget); }
        // #if MC_1211
        @Override public void render(GuiGraphics graphics, int index, int top, int left, int width, int height, int mouseX, int mouseY, boolean hovered, float partialTick) {
            renderRow(graphics, left + 2, top + 2, width - 4, mouseX, mouseY, partialTick);
        }
        private void renderRow(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY, float partialTick) {
        // #else
        @Override public void visitWidgets(Consumer<AbstractWidget> visitor) { visitor.accept(widget); }
        @Override public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
            renderRow(graphics, getContentX(), getContentY(), getContentWidth(), mouseX, mouseY, partialTick);
        }
        private void renderRow(GuiGraphicsExtractor graphics, int x, int y, int width, int mouseX, int mouseY, float partialTick) {
        // #endif
            widget.setX(x + width - controlWidth); widget.setY(y);
            var lines = font.split(name, width - controlWidth - 12);
            int textY = y + (20 - lines.size() * font.lineHeight) / 2;
            for (var line : lines) {
                // #if MC_1211
                graphics.drawString(font, line, x, textY, widget.active ? 0xffffffff : 0xff888888);
                // #else
                graphics.text(font, line, x, textY, widget.active ? 0xffffffff : 0xff888888);
                // #endif
                textY += font.lineHeight;
            }
            // #if MC_1211
            widget.render(graphics, mouseX, mouseY, partialTick);
            // #else
            widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
            // #endif
        }
    }
}
