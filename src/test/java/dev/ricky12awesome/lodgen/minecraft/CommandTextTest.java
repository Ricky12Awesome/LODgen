package dev.ricky12awesome.lodgen.minecraft;

import com.google.gson.JsonParser;
import dev.ricky12awesome.lodgen.mixin.CommandFormattingMixin;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Uses Minecraft's translation and text visitors without launching a client. */
class CommandTextTest {
    private final Language originalLanguage = Language.getInstance();

    @AfterEach void restoreLanguage() { Language.inject(originalLanguage); }

    @Test void retainsEnglishFallbackAndTranslationArgumentsOnDedicatedServers() throws Exception {
        Language.inject(language(Map.of()));
        String key = "lodgen.command.error.operation_failed";
        var component = CommandText.message(key, "disk unavailable");
        var contents = (TranslatableContents) component.getContents();
        String english;
        try (var input = getClass().getResourceAsStream("/assets/lodgen/lang/en_us.json")) {
            assertNotNull(input);
            english = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().get(key).getAsString();
        }
        assertEquals(key, contents.getKey());
        assertEquals(english, contents.getFallback());
        assertArrayEquals(new Object[]{"disk unavailable"}, contents.getArgs());
        assertEquals(String.format(english, "disk unavailable"), component.getString());
        assertFalse(CommandText.plain(component).contains("§"));
        assertTrue(CommandText.plain(component).contains("disk unavailable"));
    }

    @Test void clientTranslationsAndResourceReloadOverrideServerFallback() {
        String key = "lodgen.command.error.operation_failed";
        var component = CommandText.message(key, "detail");
        Language.inject(language(Map.of(key, "First locale: %s")));
        assertEquals("First locale: detail", component.getString());
        Language.inject(language(Map.of(key, "Reloaded locale: %s")));
        assertEquals("Reloaded locale: detail", component.getString());
        assertEquals(key, ((TranslatableContents) component.getContents()).getKey());
    }

    @Test void rendersKeyedErrorsAndKeepsUnexpectedFailureDetails() {
        Language.inject(language(Map.of("lodgen.command.error.no_task", "§cNo task§r",
                "lodgen.command.error.operation_failed", "§cFailed: %s§r")));
        assertEquals("No task", CommandText.plain(CommandText.error(new IllegalStateException("lodgen.command.error.no_task"))));
        assertEquals("Failed: disk unavailable", CommandText.plain(CommandText.error(new IllegalStateException("disk unavailable"))));
        assertEquals("Failed: IllegalStateException", CommandText.plain(CommandText.error(new IllegalStateException())));
    }

    @Test void localizesDurationUnitsAndPreservesBoundaryRemainders() {
        Language.inject(language(Map.of(
                "lodgen.command.duration.unknown", "unknown duration",
                "lodgen.command.duration.seconds", "%s seconds",
                "lodgen.command.duration.minutes", "%s minutes %s seconds",
                "lodgen.command.duration.hours", "%s hours %s minutes",
                "lodgen.command.duration.days", "%s days %s hours")));
        assertEquals("unknown duration", CommandText.duration(-1).getString());
        assertEquals("0 seconds", CommandText.duration(0).getString());
        assertEquals("59 seconds", CommandText.duration(59).getString());
        assertEquals("1 minutes 0 seconds", CommandText.duration(60).getString());
        assertEquals("59 minutes 59 seconds", CommandText.duration(3599).getString());
        assertEquals("1 hours 0 minutes", CommandText.duration(3600).getString());
        assertEquals("23 hours 59 minutes", CommandText.duration(86399).getString());
        assertEquals("1 days 0 hours", CommandText.duration(86400).getString());
        assertEquals("2 days 3 hours", CommandText.duration(183600).getString());
        var component = CommandText.duration(75);
        Language.inject(language(Map.of("lodgen.command.duration.minutes", "%s min %s sec")));
        assertEquals("1 min 15 sec", component.getString());
    }

    @Test void formattingSurvivesPlaceholdersAndResetRestoresParentStyle() {
        String key = "lodgen.command.fixture";
        Language.inject(language(Map.of(key, "§6before %s after§r!")));
        var contents = new TranslatableContents(key, null, new Object[]{Component.literal("VALUE")});
        var mixin = new VisitorFixture(contents);
        Style parent = Style.EMPTY.withColor(ChatFormatting.AQUA).withBold(true);
        List<Style> styles = new ArrayList<>();
        StringBuilder visible = new StringBuilder();
        var callback = mixin.render((style, text) -> {
            assertSame(parent, style);
            StringDecomposer.iterateFormatted(text, style, (index, current, codepoint) -> {
                visible.appendCodePoint(codepoint);
                styles.add(current);
                return true;
            });
            return Optional.empty();
        }, parent);
        assertTrue(callback.isCancelled());
        assertEquals("before VALUE after!", visible.toString());
        for (int index = 0; index < styles.size() - 1; index++) {
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD), styles.get(index).getColor());
        }
        assertEquals(parent, styles.getLast());

        Language.inject(language(Map.of(key, "§c%s§r")));
        var reloaded = mixin.render((style, text) -> Optional.of(text), parent);
        assertEquals(Optional.of("§cVALUE§r"), reloaded.getReturnValue());
    }

    @Test void leavesOtherTranslationsAloneAndHonorsVisitorStop() {
        var other = new VisitorFixture(new TranslatableContents("other.mod.key", "§6%s", new Object[]{"arg"}));
        assertFalse(other.render((style, text) -> { fail("Unrelated translations must retain their original visitor");
            return Optional.empty(); }, Style.EMPTY).isCancelled());
        var command = new VisitorFixture(new TranslatableContents("lodgen.command.fixture", "%s", new Object[]{"arg"}));
        assertEquals(Optional.of("stop"), command.render((style, text) -> Optional.of("stop"), Style.EMPTY).getReturnValue());
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

    /** Invoke the actual injected body while its shadow methods delegate to real translation contents. */
    private static final class VisitorFixture extends CommandFormattingMixin {
        private final TranslatableContents contents;

        VisitorFixture(TranslatableContents contents) { this.contents = contents; }
        @Override public String getKey() { return contents.getKey(); }
        @Override public <T> Optional<T> visit(FormattedText.ContentConsumer<T> consumer) { return contents.visit(consumer); }
        <T> CallbackInfoReturnable<Optional<T>> render(FormattedText.StyledContentConsumer<T> consumer, Style style) {
            var callback = new CallbackInfoReturnable<Optional<T>>("visit", true);
            lodgen$commandFormatting(consumer, style, callback);
            return callback;
        }
    }
}
