package dev.lodgen.client;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationCenter;
// #if MC_1211
import net.minecraft.client.gui.GuiGraphics;
// #else
import net.minecraft.client.gui.GuiGraphicsExtractor;
// #endif
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** All settings share one scrollable list and one draft. */
public final class LodgenConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 28;
    private final Screen parent;
    private boolean enabled = LodgenConfig.INSTANCE.enabled();
    private int cpuLoad = LodgenConfig.INSTANCE.cpuLoad();
    private boolean throughput = LodgenConfig.INSTANCE.showChunksPerSecond();
    private GenerationCenter center = LodgenConfig.INSTANCE.generationCenter();
    private dev.lodgen.generation.CaveMode caveMode = LodgenConfig.INSTANCE.caveMode();
    private final Map<String, String> numbers = new HashMap<>();
    private final Map<String, EditBox> inputs = new HashMap<>();
    private SettingsList settings;
    private Component error;
    private int rowWidth, controlWidth;

    public LodgenConfigScreen(Screen parent) {
        super(Component.translatable("lodgen.config.title"));
        this.parent = parent;
        setNumbers(LodgenConfig.INSTANCE);
    }
    private void setNumbers(LodgenConfig values) {
        numbers.put("generationDistance", Integer.toString(values.generationDistance()));
        numbers.put("savedChunkRadius", Integer.toString(values.savedChunkRadius()));
        numbers.put("centerX", Integer.toString(values.centerX()));
        numbers.put("centerZ", Integer.toString(values.centerZ()));
        numbers.put("chunksPerSecondUpdateIntervalMs", Integer.toString(values.chunksPerSecondUpdateIntervalMs()));
    }
    @Override protected void init() {
        double scroll = settings == null ? 0 : settings.position();
        rowWidth = Math.min(440, width - 40);
        controlWidth = Math.min(200, rowWidth / 2);
        inputs.clear();
        settings = addRenderableWidget(new SettingsList());
        toggleButton("enabled", () -> enabled, value -> enabled = value);
        settings.add("generationCenter", Button.builder(centerValue(), button -> {
            center = center.next(); button.setMessage(centerValue());
            inputs.get("centerX").active = inputs.get("centerZ").active = center == GenerationCenter.CUSTOM;
        }).bounds(0, 0, controlWidth, 20).tooltip(tip("generationCenter"))
                .createNarration(ignored -> label("generationCenter").copy().append(": ").append(centerValue())).build());
        numberInput("centerX").active = center == GenerationCenter.CUSTOM;
        numberInput("centerZ").active = center == GenerationCenter.CUSTOM;
        numberInput("generationDistance");
        numberInput("savedChunkRadius");
        settings.add("caveMode", Button.builder(caveValue(), button -> {
            caveMode = caveMode.next(); button.setMessage(caveValue());
        }).bounds(0, 0, controlWidth, 20).tooltip(tip("caveMode"))
                .createNarration(ignored -> label("caveMode").copy().append(": ").append(caveValue())).build());
        var load = Button.builder(cpuValue(), button -> {
            cpuLoad = cpuLoad % 5 + 1; button.setMessage(cpuValue());
        }).bounds(0, 0, controlWidth, 20).tooltip(tip("cpuLoad"))
                .createNarration(ignored -> label("cpuLoad").copy().append(": ").append(cpuValue())).build();
        load.active = !dev.lodgen.ModSupport.loaded("distanthorizons");
        settings.add("cpuLoad", load);
        toggleButton("showChunksPerSecond", () -> throughput, value -> throughput = value);
        numberInput("chunksPerSecondUpdateIntervalMs");
        settings.setScrollAmount(scroll);

        int left = (width - rowWidth) / 2, footer = height - 28, buttonWidth = (rowWidth - 8) / 3;
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.defaults"), button -> reset()).bounds(left, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose()).bounds(left + buttonWidth + 4, footer, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("lodgen.config.apply"), button -> apply()).bounds(left + 2 * (buttonWidth + 4), footer, buttonWidth, 20).build());
    }
    private void toggleButton(String key, Supplier<Boolean> value, Consumer<Boolean> update) {
        settings.add(key, Button.builder(onOff(value.get()), button -> {
            update.accept(!value.get()); button.setMessage(onOff(value.get()));
        }).bounds(0, 0, controlWidth, 20).tooltip(tip(key))
                .createNarration(ignored -> label(key).copy().append(": ").append(onOff(value.get()))).build());
    }
    private EditBox numberInput(String key) {
        var input = new EditBox(font, 0, 0, controlWidth, 20, label(key));
        input.setMaxLength(11); input.setValue(numbers.get(key)); input.setTooltip(tip(key));
        input.setResponder(value -> numbers.put(key, value));
        inputs.put(key, input);
        settings.add(key, input);
        return input;
    }
    private Component cpuValue() {
        return Component.translatable(dev.lodgen.ModSupport.loaded("distanthorizons") ? "lodgen.config.cpuLoad.dh" : "lodgen.config.cpuLoad." + cpuLoad);
    }
    private Component centerValue() {
        return Component.translatable("lodgen.config.center." + center.name().toLowerCase(java.util.Locale.ROOT));
    }
    private Component caveValue() {
        return Component.translatable("lodgen.config.cave." + caveMode.name().toLowerCase(java.util.Locale.ROOT));
    }
    private static Component label(String key) { return Component.translatable("lodgen.config." + key); }
    private static Tooltip tip(String key) { return Tooltip.create(Component.translatable("lodgen.config." + key + ".tooltip")); }
    private static Component onOff(boolean value) { return Component.translatable(value ? "options.on" : "options.off"); }
    private int number(String key) { return Integer.parseInt(numbers.get(key)); }
    private void reset() {
        var defaults = LodgenConfig.DEFAULTS;
        enabled = defaults.enabled(); cpuLoad = defaults.cpuLoad(); throughput = defaults.showChunksPerSecond(); center = defaults.generationCenter();
        caveMode = defaults.caveMode();
        setNumbers(defaults); error = null; rebuildWidgets();
    }
    private void apply() {
        try {
            LodgenConfig.apply(new LodgenConfig(enabled, cpuLoad,
                    number("generationDistance"), throughput, number("chunksPerSecondUpdateIntervalMs"), center, number("centerX"), number("centerZ"), number("savedChunkRadius"), caveMode));
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
        void add(String key, AbstractWidget widget) { addEntry(new SettingRow(key, widget)); }
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
        SettingRow(String key, AbstractWidget widget) { name = label(key); this.widget = widget; }
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
