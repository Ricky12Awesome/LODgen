package dev.ricky12awesome.lodgen.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSchemaTest {
    private enum Theme { LIGHT, DARK }

    private record SmallConfig(
            @ConfigOption(defaultValue = "true", order = 0, comment = "Whether the option is enabled.")
            boolean enabled,
            @ConfigOption(defaultValue = "3", order = 1, min = -8, max = 8, cycle = true, comment = "A bounded number.")
            int amount,
            @ConfigOption(defaultValue = "dark", order = 2, valueKey = "test.theme.", comment = "The selected theme.")
            Theme theme) {}

    private enum Center { CURRENT, CUSTOM }

    private record ConditionalConfig(
            @ConfigOption(defaultValue = "current", valueKey = "test.center.", comment = "Center mode.")
            Center center,
            @ConfigOption(defaultValue = "0", min = -100, max = 100, enabledWhen = "center", enabledValue = "custom", comment = "Custom X.")
            int centerX,
            @ConfigOption(defaultValue = "2", min = 1, max = 5, cycle = true, disabledWithMod = "distanthorizons",
                    disabledValueKey = "test.cpu.dh", comment = "CPU load.")
            int cpuLoad) {}

    @Test
    void annotatedRecordDrivesDefaultsTomlMetadataAndChoices() {
        var schema = new ConfigSchema<>(SmallConfig.class, "test.config.");

        assertEquals(new SmallConfig(true, 3, Theme.DARK), schema.defaults());
        assertEquals(java.util.List.of("enabled", "amount", "theme"),
                schema.options().stream().map(ConfigSchema.Option::key).toList());
        var amount = schema.option("amount");
        var theme = schema.option("theme");
        assertEquals(ConfigSchema.Control.CHOICE, amount.control());
        assertEquals(ConfigSchema.Control.CHOICE, theme.control());
        assertEquals("test.config.theme", theme.labelKey());
        assertEquals("test.config.theme.tooltip", theme.tooltipKey());
        assertEquals("test.theme.dark", theme.valueKey(Theme.DARK));
        assertEquals(Theme.LIGHT, theme.next(Theme.DARK));
        assertEquals(4, amount.next(3));
        assertEquals(-8, amount.next(8));

        var values = CommentedConfig.inMemory();
        schema.write(values, new SmallConfig(false, -4, Theme.LIGHT));
        assertEquals(false, values.get("enabled"));
        assertEquals(-4, values.<Integer>get("amount").intValue());
        assertEquals("light", values.get("theme"));
        assertTrue(values.getComment("amount").contains("bounded number"));
        var toml = new StringWriter();
        new TomlWriter().write(values, toml);
        var parsed = new TomlParser().parse(new StringReader(toml.toString()));
        assertEquals(new SmallConfig(false, -4, Theme.LIGHT), schema.read(parsed));
    }

    @Test
    void draftKeepsPartialSignedInputAndBuildsSnapshotOnlyWhenValid() {
        var schema = new ConfigSchema<>(SmallConfig.class, "test.config.");
        var source = new SmallConfig(true, 3, Theme.DARK);
        var draft = schema.draft(source);
        var amount = schema.option("amount");

        draft.edit(amount, "-");
        assertEquals("-", draft.text(amount));
        assertThrows(NumberFormatException.class, draft::snapshot);
        assertEquals(3, source.amount(), "Editing must not mutate the immutable source record");

        draft.edit(amount, "-7");
        assertEquals(new SmallConfig(true, -7, Theme.DARK), draft.snapshot());
        assertEquals(3, source.amount());
    }

    @Test
    void draftConditionsModSpecificDisplayAndResetFollowSchemaMetadata() {
        var schema = new ConfigSchema<>(ConditionalConfig.class, "test.config.");
        var defaults = schema.defaults();
        var draft = schema.draft(defaults);
        var center = schema.option("center");
        var coordinate = schema.option("centerX");
        var cpu = schema.option("cpuLoad");

        assertFalse(draft.active(coordinate, ignored -> false));
        draft.cycle(center);
        assertTrue(draft.active(coordinate, ignored -> false));
        assertFalse(draft.active(cpu, mod -> mod.equals("distanthorizons")));
        assertEquals("test.cpu.dh", draft.valueKey(cpu, mod -> mod.equals("distanthorizons")));

        draft.edit(coordinate, "-12");
        draft.edit(cpu, "5");
        draft.reset(defaults);
        assertEquals(defaults, draft.snapshot());
        assertEquals("0", draft.text(coordinate));
    }

    @Test
    void lodgenCpuLoadRemainsEditableWithDhInstalled() {
        var schema = dev.ricky12awesome.lodgen.LodgenConfig.SCHEMA;
        var draft = schema.draft(dev.ricky12awesome.lodgen.LodgenConfig.DEFAULTS);
        var cpu = schema.option("cpuLoad");
        assertTrue(draft.active(cpu, mod -> mod.equals("distanthorizons")));
        assertEquals("lodgen.config.cpuLoad.3", draft.valueKey(cpu, mod -> mod.equals("distanthorizons")));
    }

    @Test
    void schemaWithChangesOneLodgenSettingAndPreservesCaveMode() {
        var original = new dev.ricky12awesome.lodgen.LodgenConfig(true, 3, 0, false, 1000,
                dev.ricky12awesome.lodgen.generation.GenerationCenter.CURRENT, 0, 0, 0,
                dev.ricky12awesome.lodgen.generation.CaveMode.EMPTY);

        var changed = dev.ricky12awesome.lodgen.LodgenConfig.SCHEMA.with(original, "cpuLoad", 5);

        assertEquals(5, changed.cpuLoad());
        assertEquals(original, dev.ricky12awesome.lodgen.LodgenConfig.SCHEMA.with(changed, "cpuLoad", original.cpuLoad()));
        assertEquals(dev.ricky12awesome.lodgen.generation.CaveMode.EMPTY, changed.caveMode());
        assertThrows(IllegalArgumentException.class, () -> dev.ricky12awesome.lodgen.LodgenConfig.SCHEMA.with(original, "cpuLoad", 6));
    }
}
