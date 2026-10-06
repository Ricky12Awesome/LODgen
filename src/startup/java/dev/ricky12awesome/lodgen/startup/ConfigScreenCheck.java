package dev.ricky12awesome.lodgen.startup;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ricky12awesome.lodgen.LodgenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
// #if MC_26
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
// #endif

/** Exercise real list input, scrolling and resize without applying settings or opening a world. */
public final class ConfigScreenCheck {
    private static int originalWidth, originalHeight;
    private static LodgenConfig original;
    private ConfigScreenCheck() {}

    private static ContainerObjectSelectionList<?> list(Screen screen) {
        var lists = screen.children().stream().filter(child -> child instanceof ContainerObjectSelectionList<?>).toList();
        if (lists.size() != 1 || screen.children().size() != 4) throw new AssertionError("Expected one settings list and three footer buttons");
        var list = (ContainerObjectSelectionList<?>) lists.getFirst();
        if (list.children().size() != LodgenConfig.SCHEMA.options().size() - 3) throw new AssertionError("Center and throughput settings must use compact rows");
        if (list.children().stream().mapToInt(row -> row.children().size()).sum() != LodgenConfig.SCHEMA.options().size())
            throw new AssertionError("All settings must be on the same page");
        return list;
    }
    private static AbstractWidget control(ContainerObjectSelectionList<?> list, String key) {
        var primaryOptions = LodgenConfig.SCHEMA.options().stream().filter(option -> switch (option.key()) {
            case "centerX", "centerZ", "chunksPerSecondUpdateIntervalMs" -> false;
            default -> true;
        }).toList();
        int index = primaryOptions.indexOf(LodgenConfig.SCHEMA.option(key));
        return (AbstractWidget) list.children().get(index).children().getFirst();
    }
    private static EditBox input(ContainerObjectSelectionList<?> list, String key) {
        var option = LodgenConfig.SCHEMA.option(key);
        String label = Component.translatable(option.labelKey()).getString();
        return list.children().stream().flatMap(row -> row.children().stream())
                .filter(child -> child instanceof EditBox).map(child -> (EditBox) child)
                .filter(box -> box.getMessage().getString().equals(label)).findFirst().orElseThrow();
    }
    private static void press(Button button) {
        // #if MC_1211
        button.onPress();
        // #else
        button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
        // #endif
    }
    private static double scroll(ContainerObjectSelectionList<?> list) {
        // #if MC_1211
        return list.getScrollAmount();
        // #else
        return list.scrollAmount();
        // #endif
    }
    private static int maxScroll(ContainerObjectSelectionList<?> list) {
        // #if MC_1211
        return list.getMaxScroll();
        // #else
        return list.maxScrollAmount();
        // #endif
    }
    private static void resize(Minecraft minecraft, Screen screen, int width, int height) {
        // #if MC_1211
        screen.resize(minecraft, width, height);
        // #else
        screen.resize(width, height);
        // #endif
    }
    public static void begin(Minecraft minecraft, Screen screen) {
        original = LodgenConfig.INSTANCE;
        originalWidth = screen.width; originalHeight = screen.height;
        var list = list(screen);
        boolean custom = original.generationCenter() == dev.ricky12awesome.lodgen.generation.GenerationCenter.CUSTOM;
        if (!input(list, "centerX").visible || !input(list, "centerZ").visible
                || input(list, "centerX").active != custom || input(list, "centerZ").active != custom)
            throw new AssertionError("Coordinates must stay visible and editable only in X/Y mode");
        var center = (Button) control(list, "generationCenter");
        var centerRow = list.children().stream().filter(row -> row.children().contains(center)).findFirst().orElseThrow();
        if (centerRow.children().size() != 3 || !centerRow.children().contains(input(list, "centerX"))
                || !centerRow.children().contains(input(list, "centerZ"))) throw new AssertionError("Center coordinates must always share the mode button's row");
        var throughput = control(list, "showChunksPerSecond");
        var throughputRow = list.children().stream().filter(row -> row.children().contains(throughput)).findFirst().orElseThrow();
        if (throughputRow.children().size() != 2 || !throughputRow.children().contains(input(list, "chunksPerSecondUpdateIntervalMs")))
            throw new AssertionError("Throughput toggle and interval must share a row");
        var caves = (Button) control(list, "caveMode");
        var caveLabel = caves.getMessage();
        for (int i = 0; i < 3; i++) press(caves);
        if (!caveLabel.equals(caves.getMessage())) throw new AssertionError("Cave mode did not cycle through all three choices");
        var cpu = (Button) control(list, "cpuLoad");
        if (!cpu.active) throw new AssertionError("LODgen CPU load is disabled");
        var initial = cpu.getMessage();
        for (int i = 0; i < 5; i++) press(cpu);
        if (!initial.equals(cpu.getMessage())) throw new AssertionError("CPU load did not cycle through all five levels");
        var centerLabel = center.getMessage();
        for (int i = 0; i < 2; i++) {
            press(center);
            if (!center.getMessage().equals(Component.translatable("lodgen.config.option.center_mode.value.origin"))
                    && !center.getMessage().equals(Component.translatable("lodgen.config.option.center_mode.value.custom")))
                throw new AssertionError("Center button must offer only Origin and X/Y");
        }
        if (!centerLabel.equals(center.getMessage())) throw new AssertionError("Center button must cycle through exactly two choices");
        if (!input(list, "centerX").active) press(center);
        if (!input(list, "centerX").active || !input(list, "centerZ").active) throw new AssertionError("Custom center did not enable X/Z");
        var centerX = input(list, "centerX");
        var centerZ = input(list, "centerZ");
        var distance = input(list, "generationDistance");
        if (center.getX() != distance.getX() || center.getWidth() > 60
                || center.getX() + center.getWidth() >= centerX.getX()
                || centerX.getX() + centerX.getWidth() >= centerZ.getX()
                || centerZ.getX() + centerZ.getWidth() > distance.getX() + distance.getWidth())
            throw new AssertionError("Center controls must fit the same width as other settings");
        centerX.setValue("-123"); centerZ.setValue("456");
        centerX.setFocused(true);
        press(center);
        if (!centerX.visible || !centerZ.visible || centerX.active || centerZ.active || centerX.isFocused()
                || !centerRow.children().contains(centerX) || !centerRow.children().contains(centerZ))
            throw new AssertionError("Origin mode must keep coordinates visible and disable editing");
        press(center);
        if (!input(list, "centerX").getValue().equals("-123") || !input(list, "centerZ").getValue().equals("456"))
            throw new AssertionError("Changing center mode discarded custom coordinates");
        input(list, "generationDistance").setValue("64");
        resize(minecraft, screen, 320, 240);
        list = list(screen);
        if (!input(list, "centerX").getValue().equals("-123") || !input(list, "centerZ").getValue().equals("456")
                || !input(list, "generationDistance").getValue().equals("64") || !input(list, "centerX").active)
            throw new AssertionError("Resize discarded unsaved edits or custom center");
        if (!list.mouseScrolled(160, 100, 0, -100) || scroll(list) <= 0) throw new AssertionError("Mouse wheel did not reach lower settings");
    }
    public static void finish(Minecraft minecraft, Screen screen) {
        var list = list(screen);
        var interval = input(list, "chunksPerSecondUpdateIntervalMs");
        if (interval.getY() < list.getY() || interval.getY() + interval.getHeight() > list.getY() + list.getHeight())
            throw new AssertionError("Bottom setting is clipped after scrolling");
        double x = interval.getX() + 5, y = interval.getY() + 10;
        // #if MC_1211
        if (!screen.mouseClicked(x, y, InputConstants.MOUSE_BUTTON_LEFT)) throw new AssertionError("Scrolled input did not accept mouse click");
        // #else
        if (!screen.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, 0)), false)) throw new AssertionError("Scrolled input did not accept mouse click");
        // #endif
        if (!interval.isFocused()) throw new AssertionError("Scrolled input did not receive focus");
        interval.setValue("250");
        double previousScroll = scroll(list);
        resize(minecraft, screen, originalWidth, originalHeight);
        if (!input(list(screen), "chunksPerSecondUpdateIntervalMs").getValue().equals("250")
                || Math.abs(scroll(list(screen)) - Math.min(previousScroll, maxScroll(list(screen)))) > 0.01)
            throw new AssertionError("Resize discarded lower draft input or scroll position");
        if (LodgenConfig.INSTANCE != original) throw new AssertionError("Draft edits changed live settings before Apply");
    }
}
