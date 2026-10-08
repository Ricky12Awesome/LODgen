package dev.ricky12awesome.lodgen.minecraft;

import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Exercise server output with Minecraft's text visitors and no client language resources. */
class CommandTextTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(?:(\\d+)\\$)?s");
    private final Language originalLanguage = Language.getInstance();

    @AfterEach void restoreLanguage() { Language.inject(originalLanguage); }

    @Test void serverFormatsMessagesBeforeClientsReceiveThem() {
        Language.inject(language(Map.of()));
        var component = CommandText.message("lodgen.command.error.operation_failed", "disk unavailable");
        assertFalse(component.getContents() instanceof TranslatableContents);
        assertEquals("LODgen failed: disk unavailable", component.getString());

        Language.inject(language(Map.of("lodgen.command.error.operation_failed", "Client override: %s")));
        assertEquals("LODgen failed: disk unavailable", component.getString());
        assertEquals("LODgen failed: disk unavailable",
                CommandText.message("lodgen.command.error.operation_failed", "disk unavailable").getString());
    }

    @Test void statusFormatsAllIndexedAndNestedArgumentsIncludingPercent() {
        Language.inject(language(Map.of()));
        for (String type : List.of("task", "no_task")) {
            var component = CommandText.message("lodgen.command.status." + type,
                    CommandText.message("lodgen.command.status.automatic_prefix"),
                    CommandText.message("lodgen.command.state.paused"),
                    "minecraft:the_nether", 32, -48, 5, 80, 1, 16,
                    CommandText.message("lodgen.command.cave_mode.fill"),
                    25L, 100L, "25.00", 2, "123.4", CommandText.duration(75),
                    CommandText.message("lodgen.command.status.error_suffix", "disk 100% full"));
            assertFalse(component.getContents() instanceof TranslatableContents);
            assertEquals("LODgen" + (type.equals("no_task") ? " (Autostart)" : "")
                    + ": automatic Paused (2 active)\nDimension: minecraft:the_nether\nCenter X/Z: 32 -48"
                    + "\nRadius: 5c (80 blocks)\nRadius (Saved): 1c (16 blocks)\nCaves: fill"
                    + "\nProgress: 25/100 chunks (25.00%)\nChunks/s: 123.4"
                    + "\nETA: 1m 15s; error=disk 100% full", CommandText.plain(component));
        }
    }

    @Test void everyPlaceholderTemplateResolvesWithoutClientResources() throws Exception {
        Language.inject(language(Map.of()));
        try (var input = getClass().getResourceAsStream("/assets/lodgen/lang/en_us.json")) {
            assertNotNull(input);
            var translations = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            for (var entry : translations.entrySet()) {
                var matcher = PLACEHOLDER.matcher(entry.getValue().getAsString());
                int count = 0, sequential = 0;
                while (matcher.find()) {
                    int index = matcher.group(1) == null ? ++sequential : Integer.parseInt(matcher.group(1));
                    count = Math.max(count, index);
                }
                if (count == 0) continue;
                Object[] values = new Object[count];
                for (int index = 0; index < count; index++) values[index] = Component.literal("value" + (index + 1));
                var rendered = CommandText.message(entry.getKey(), values);
                assertFalse(rendered.getContents() instanceof TranslatableContents, entry.getKey());
                assertFalse(PLACEHOLDER.matcher(rendered.getString()).find(), entry.getKey());
                assertFalse(rendered.getString().contains("%%"), entry.getKey());
                for (int index = 0; index < count; index++)
                    assertTrue(rendered.getString().contains("value" + (index + 1)), entry.getKey());
            }
        }
    }

    @Test void errorsPreserveDetailsWithoutFormattingThemAgain() {
        Language.inject(language(Map.of()));
        assertEquals("No LODgen task", CommandText.plain(CommandText.error(new IllegalStateException("lodgen.command.error.no_task"))));
        assertEquals("LODgen failed: disk 100% full: %1$s", CommandText.plain(CommandText.error(new IllegalStateException("disk 100% full: %1$s"))));
        assertEquals("LODgen failed: IllegalStateException", CommandText.plain(CommandText.error(new IllegalStateException())));
    }

    @Test void vssGenerationWarningKeepsItsColorsAndServerPlainText() {
        var warning = CommandText.message("lodgen.warning.vss_generation_disabled");
        assertEquals("\u00a7cLODgen has disabled VSS generation, set \u00a7b'generation.enabled'"
                + "\u00a7c to \u00a7dfalse\u00a7c in \u00a7bconfig/vss-server-config.yaml"
                + "\u00a7con sever to get rid of this warning", warning.getString());
        assertEquals("LODgen has disabled VSS generation, set 'generation.enabled' to false"
                + " in config/vss-server-config.yamlon sever to get rid of this warning",
                CommandText.plain(warning));
    }

    @Test void durationUsesServerUnitsAndPreservesBoundaryRemainders() {
        Language.inject(language(Map.of()));
        assertEquals("—", CommandText.duration(-1).getString());
        assertEquals("0s", CommandText.duration(0).getString());
        assertEquals("59s", CommandText.duration(59).getString());
        assertEquals("1m 0s", CommandText.duration(60).getString());
        assertEquals("59m 59s", CommandText.duration(3599).getString());
        assertEquals("1h 0m", CommandText.duration(3600).getString());
        assertEquals("23h 59m", CommandText.duration(86399).getString());
        assertEquals("1d 0h", CommandText.duration(86400).getString());
        assertEquals("2d 3h", CommandText.duration(183600).getString());
    }

    @Test void actionBarAndColorsRenderOnVanillaClients() {
        Language.inject(language(Map.of()));
        var component = CommandText.message("lodgen.overlay.message", "123.4", 64, CommandText.duration(123),
                CommandText.message("lodgen.overlay.state.running"));
        assertFalse(component.getContents() instanceof TranslatableContents);
        assertEquals("LODgen: 123.4 chunks/s | Radius: 64c | ETA: 2m 3s | Running", CommandText.plain(component));

        Style parent = Style.EMPTY.withColor(ChatFormatting.WHITE);
        List<Style> styles = new ArrayList<>();
        StringBuilder visible = new StringBuilder();
        component.visit((style, text) -> {
            StringDecomposer.iterateFormatted(text, style, (index, current, codepoint) -> {
                visible.appendCodePoint(codepoint);
                styles.add(current);
                return true;
            });
            return Optional.empty();
        }, parent);
        assertEquals(CommandText.plain(component), visible.toString());
        int rateStart = visible.indexOf("123.4");
        for (int index = rateStart; index < rateStart + "123.4".length(); index++)
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), styles.get(index).getColor());
        assertEquals(parent, styles.get(visible.indexOf("64")));
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), styles.get(styles.size() - 1).getColor());
    }

    private static Language language(Map<String, String> translations) {
        return new Language() {
            @Override public String getOrDefault(String key, String fallback) { return translations.getOrDefault(key, fallback); }
            @Override public boolean has(String key) { return translations.containsKey(key); }
            @Override public boolean isDefaultRightToLeft() { return false; }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) {
                return FormattedCharSequence.forward(text.getString(), Style.EMPTY);
            }
        };
    }
}
