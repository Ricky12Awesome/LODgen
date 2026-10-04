package dev.lodgen.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Discovers a record's options once; adding a field needs no persistence or UI wiring. */
public final class ConfigSchema<R extends Record> {
    private final Constructor<R> constructor;
    private final List<Option> fields;
    private final List<Option> options;

    public ConfigSchema(Class<R> type, String translationPrefix) {
        var components = type.getRecordComponents();
        fields = Arrays.stream(components).map(component -> new Option(component, translationPrefix)).toList();
        options = fields.stream().sorted(Comparator.comparingInt(option -> option.metadata.order())).toList();
        for (var option : options) {
            option.parse(option.metadata.defaultValue());
            if (!option.metadata.enabledWhen().isEmpty())
                option(option.metadata.enabledWhen()).parse(option.metadata.enabledValue());
        }
        try {
            constructor = type.getDeclaredConstructor(Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            constructor.setAccessible(true);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot construct configuration " + type.getName(), failure);
        }
    }

    public List<Option> options() { return options; }

    public Option option(String key) {
        return fields.stream().filter(option -> option.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown config option: " + key));
    }

    public R defaults() {
        return create(fields.stream().map(option -> option.parse(option.metadata.defaultValue())).toArray());
    }

    public R read(UnmodifiableConfig values) {
        return create(fields.stream().map(option -> option.decode(values.get(option.key()))).toArray());
    }

    /** Update known keys while preserving unrelated settings and tables. */
    public void write(CommentedConfig values, R settings) {
        for (var option : options) {
            values.set(option.key(), option.encode(option.value(settings)));
            values.setComment(option.key(), " " + option.metadata.comment());
        }
    }

    /** Used by a record's compact constructor so direct callers share the same bounds. */
    public void validateValues(Object... values) {
        if (values.length != fields.size()) throw new IllegalArgumentException("Expected " + fields.size() + " config values");
        for (int i = 0; i < values.length; i++) fields.get(i).validate(values[i]);
    }

    public R with(R settings, String key, Object value) {
        var option = option(key);
        var values = fields.stream().map(field -> field == option ? value : field.value(settings)).toArray();
        return create(values);
    }

    public ConfigDraft<R> draft(R settings) { return new ConfigDraft<>(this, settings); }

    R fromDraft(ConfigDraft<R> draft) {
        return create(fields.stream().map(option -> option.parse(draft.text(option))).toArray());
    }

    private R create(Object[] values) {
        validateValues(values);
        try {
            return constructor.newInstance(values);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException("Cannot construct configuration", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot construct configuration", failure);
        }
    }

    public enum Control { TOGGLE, NUMBER, CHOICE }

    public static final class Option {
        private final RecordComponent component;
        private final Method accessor;
        private final ConfigOption metadata;
        private final String labelKey;
        private final List<?> choices;

        private Option(RecordComponent component, String translationPrefix) {
            this.component = component;
            accessor = component.getAccessor();
            metadata = Objects.requireNonNull(component.getAnnotation(ConfigOption.class), "Missing @ConfigOption on " + key());
            labelKey = translationPrefix + key();
            accessor.setAccessible(true);
            if (component.getType() != boolean.class && component.getType() != int.class && !component.getType().isEnum())
                throw new IllegalArgumentException("Unsupported config type: " + component.getType());
            if (metadata.min() > metadata.max()) throw new IllegalArgumentException("Invalid bounds for " + key());
            choices = component.getType().isEnum() ? List.of(component.getType().getEnumConstants()) : List.of();
        }

        public String key() { return component.getName(); }
        public String labelKey() { return labelKey; }
        public String label() { return metadata.label().isEmpty() ? humanize(key()) : metadata.label(); }
        public String tooltipKey() { return labelKey + ".tooltip"; }
        public ConfigOption metadata() { return metadata; }
        public Control control() {
            if (component.getType() == boolean.class) return Control.TOGGLE;
            return component.getType().isEnum() || metadata.cycle() ? Control.CHOICE : Control.NUMBER;
        }

        public Object value(Record settings) {
            try {
                return accessor.invoke(settings);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read option " + key(), failure);
            }
        }

        Object decode(Object value) {
            if (value == null) return parse(metadata.defaultValue());
            if (component.getType() == int.class && value instanceof Long number) value = Math.toIntExact(number);
            if (component.getType().isEnum()) {
                if (!(value instanceof String text)) throw new IllegalArgumentException(key() + " must be a TOML string");
                return parse(text);
            }
            validate(value);
            return value;
        }

        Object parse(String text) {
            Object value;
            if (component.getType() == boolean.class) {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false"))
                    throw new IllegalArgumentException(key() + " must be a boolean");
                value = Boolean.parseBoolean(text);
            } else if (component.getType() == int.class) {
                value = Integer.parseInt(text);
            } else {
                value = choices.stream().filter(choice -> ((Enum<?>) choice).name().equalsIgnoreCase(text)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown " + key() + ": " + text));
            }
            validate(value);
            return value;
        }

        Object encode(Object value) {
            return value instanceof Enum<?> choice ? choice.name().toLowerCase(Locale.ROOT) : value;
        }

        private void validate(Object value) {
            Objects.requireNonNull(value, key());
            if (component.getType() == boolean.class && value instanceof Boolean) return;
            if (component.getType().isEnum() && component.getType().isInstance(value)) return;
            if (component.getType() == int.class && value instanceof Integer number) {
                if (number < metadata.min() || number > metadata.max())
                    throw new IllegalArgumentException(key() + " must be " + metadata.min() + "–" + metadata.max());
                return;
            }
            throw new IllegalArgumentException("Invalid type for " + key());
        }

        Object next(Object value) {
            if (value instanceof Boolean toggle) return !toggle;
            if (value instanceof Integer number && metadata.cycle()) return number == metadata.max() ? metadata.min() : number + 1;
            if (!choices.isEmpty()) return choices.get((choices.indexOf(value) + 1) % choices.size());
            throw new IllegalArgumentException(key() + " is not a choice");
        }

        public String valueKey(Object value) {
            if (value instanceof Boolean toggle) return toggle ? "options.on" : "options.off";
            String prefix = metadata.valueKey().isEmpty() ? labelKey + "." : metadata.valueKey();
            return prefix + encode(value);
        }

        public String valueLabel(Object value) {
            if (value instanceof Boolean toggle) return toggle ? "On" : "Off";
            return value instanceof Enum<?> choice ? humanize(choice.name().toLowerCase(Locale.ROOT)) : value.toString();
        }

        private static String humanize(String text) {
            String words = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ');
            return Character.toUpperCase(words.charAt(0)) + words.substring(1);
        }
    }
}
