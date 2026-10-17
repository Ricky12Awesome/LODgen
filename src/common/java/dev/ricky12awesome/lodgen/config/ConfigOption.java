package dev.ricky12awesome.lodgen.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Metadata shared by TOML persistence, validation, and config controls. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface ConfigOption {
    String defaultValue();
    String comment();
    int order() default Integer.MAX_VALUE;
    /** TOML path; defaults to the record field name. */
    String path() default "";
    /** File order can differ from the order of controls in the config screen. */
    int fileOrder() default Integer.MAX_VALUE;
    /** Composite settings can keep fields in the UI without separate TOML keys. */
    boolean persisted() default true;
    int min() default Integer.MIN_VALUE;
    int max() default Integer.MAX_VALUE;
    /** Bound a thread count to the logical processors available to this JVM. */
    boolean maxAvailableProcessors() default false;
    boolean slider() default false;
    boolean cycle() default false;
    /** Language-key name; defaults to the record field name in snake_case. */
    String translationName() default "";
    String valueKey() default "";
    String enabledWhen() default "";
    String enabledValue() default "";
    String disabledWithMod() default "";
    String disabledValueKey() default "";
}
