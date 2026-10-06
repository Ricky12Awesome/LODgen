package dev.ricky12awesome.lodgen.minecraft;

import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Translations retain their keys for clients and English fallbacks for dedicated servers. */
public final class CommandText {
    private static final Map<String, String> ENGLISH = loadEnglish();

    private CommandText() {}

    public static MutableComponent message(String key, Object... args) {
        return Component.translatableWithFallback(key, ENGLISH.get(key), args);
    }

    public static MutableComponent error(Throwable error) {
        String detail = error.getMessage();
        if (detail != null && detail.startsWith("lodgen.command.")) return message(detail);
        return message("lodgen.command.error.failed", detail == null ? error.getClass().getSimpleName() : detail);
    }

    public static MutableComponent duration(long seconds) {
        if (seconds < 0) return message("lodgen.command.duration.unknown");
        if (seconds < 60) return message("lodgen.command.duration.seconds", seconds);
        if (seconds < 3600) return message("lodgen.command.duration.minutes", seconds / 60, seconds % 60);
        if (seconds < 86400) return message("lodgen.command.duration.hours", seconds / 3600, seconds / 60 % 60);
        return message("lodgen.command.duration.days", seconds / 86400, seconds / 3600 % 24);
    }

    /** Logs and string-based status integrations do not interpret legacy formatting codes. */
    public static String plain(Component component) {
        return ChatFormatting.stripFormatting(component.getString());
    }

    private static Map<String, String> loadEnglish() {
        try (var input = CommandText.class.getResourceAsStream("/assets/lodgen/lang/en_us.json")) {
            if (input == null) throw new IllegalStateException("Missing LODgen English language resource");
            var result = new HashMap<String, String>();
            JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().entrySet().forEach(entry -> result.put(entry.getKey(), entry.getValue().getAsString()));
            return Map.copyOf(result);
        } catch (IOException error) {
            throw new IllegalStateException("Could not load LODgen English language resource", error);
        }
    }
}
