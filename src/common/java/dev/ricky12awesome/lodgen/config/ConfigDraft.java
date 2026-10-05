package dev.ricky12awesome.lodgen.config;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Keeps unfinished numeric input separate from the immutable live configuration. */
public final class ConfigDraft<R extends Record> {
    private final ConfigSchema<R> schema;
    private final Map<String, String> values = new HashMap<>();

    ConfigDraft(ConfigSchema<R> schema, R settings) {
        this.schema = schema;
        reset(settings);
    }

    public void reset(R settings) {
        for (var option : schema.options()) values.put(option.key(), option.encode(option.value(settings)).toString());
    }

    public String text(ConfigSchema.Option option) { return values.get(option.key()); }
    public void edit(ConfigSchema.Option option, String text) { values.put(option.key(), text); }

    public void cycle(ConfigSchema.Option option) {
        values.put(option.key(), option.encode(option.next(option.parse(text(option)))).toString());
    }

    public boolean active(ConfigSchema.Option option, Predicate<String> modLoaded) {
        var metadata = option.metadata();
        if (!metadata.disabledWithMod().isEmpty() && modLoaded.test(metadata.disabledWithMod())) return false;
        return metadata.enabledWhen().isEmpty()
                || values.get(metadata.enabledWhen()).equalsIgnoreCase(metadata.enabledValue());
    }

    public String valueKey(ConfigSchema.Option option, Predicate<String> modLoaded) {
        var metadata = option.metadata();
        if (!metadata.disabledWithMod().isEmpty() && modLoaded.test(metadata.disabledWithMod()) && !metadata.disabledValueKey().isEmpty())
            return metadata.disabledValueKey();
        return option.valueKey(option.parse(text(option)));
    }

    public String valueLabel(ConfigSchema.Option option) { return option.valueLabel(option.parse(text(option))); }

    public R snapshot() { return schema.fromDraft(this); }
}
