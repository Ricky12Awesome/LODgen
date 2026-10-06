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
        if (list.children().size() != LodgenConfig.SCHEMA.options().size()) throw new AssertionError("All settings must be on the same page");
        return list;
    }
    private static AbstractWidget control(ContainerObjectSelectionList<?> list, String key) {
        int index = LodgenConfig.SCHEMA.options().indexOf(LodgenConfig.SCHEMA.option(key));
        var row = list.children().get(index);
        if (row.children().size() != 1) throw new AssertionError("Settings must have one control per row");
        return (AbstractWidget) row.children().getFirst();
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
        if (input(list, "centerX").active != custom || input(list, "centerZ").active != custom) throw new AssertionError("Custom coordinate activation differs from configured center");
        var center = (Button) control(list, "generationCenter");
        var caves = (Button) control(list, "caveMode");
        var caveLabel = caves.getMessage();
        for (int i = 0; i < 3; i++) press(caves);
        if (!caveLabel.equals(caves.getMessage())) throw new AssertionError("Cave mode did not cycle through all three choices");
        var cpu = (Button) control(list, "cpuLoad");
        if (!cpu.active) throw new AssertionError("LODgen CPU load is disabled");
        var initial = cpu.getMessage();
        for (int i = 0; i < 5; i++) press(cpu);
        if (!initial.equals(cpu.getMessage())) throw new AssertionError("CPU load did not cycle through all five levels");
        var centerMode = original.generationCenter();
        for (int i = 0; i < 3; i++) { press(center); centerMode = centerMode.next(); }
        while (centerMode != dev.ricky12awesome.lodgen.generation.GenerationCenter.CUSTOM) { press(center); centerMode = centerMode.next(); }
        if (!input(list, "centerX").active || !input(list, "centerZ").active) throw new AssertionError("Custom center did not enable X/Z");
        input(list, "centerX").setValue("-123"); input(list, "centerZ").setValue("456");
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
