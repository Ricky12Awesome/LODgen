# Configuration code

Settings are declared on the record components in
[`LodgenConfig`](../../src/common/java/dev/ricky12awesome/lodgen/LodgenConfig.java).
Each `@ConfigOption` supplies its default, bounds, label, description, and any
control conditions. The same metadata drives TOML loading and saving, defaults,
validation, and the in-game controls.

For example, a bounded integer becomes a text input automatically:

```java
@ConfigOption(defaultValue = "1000", label = "Overlay update interval (ms)",
        min = 1, max = 60000, comment = "Milliseconds between action-bar updates.")
int chunksPerSecondUpdateIntervalMs
```

When adding a component, include its parameter in the compact constructor's
`SCHEMA.validateValues(...)` call and keep compatibility constructors in sync
with the record signature. No option-specific loading, saving, draft, reset, or
screen code is needed. Configuration consumers continue using typed record
accessors.

Booleans become toggles, enums become cycling buttons, and integers become text
inputs. Use `cycle = true` for an integer that should cycle between its minimum
and maximum instead. `order` sets the row order independently of constructor
argument order.

`enabledWhen` and `enabledValue` make a control depend on another option, as with
custom coordinates. `disabledWithMod` and `disabledValueKey` let an installed
mod supply the setting, as with DH's CPU load. The renderer applies these rules
to every control after edits.

Labels and descriptions live in the annotation. Language resources can override
`lodgen.config.<name>` and `lodgen.config.<name>.tooltip`; English entries are
optional. Choice labels use `valueKey` or `lodgen.config.<name>.<value>`, with a
readable fallback for untranslated values.

`ConfigDraft` keeps unfinished text separate from live settings and validates
when creating a snapshot. `SCHEMA.with(settings, key, value)` creates a validated
copy with one value changed, preserving every other component automatically.
