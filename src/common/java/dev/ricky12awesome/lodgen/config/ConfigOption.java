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
    int min() default Integer.MIN_VALUE;
    int max() default Integer.MAX_VALUE;
    boolean cycle() default false;
    String valueKey() default "";
    String enabledWhen() default "";
    String enabledValue() default "";
    String disabledWithMod() default "";
    String disabledValueKey() default "";
}
