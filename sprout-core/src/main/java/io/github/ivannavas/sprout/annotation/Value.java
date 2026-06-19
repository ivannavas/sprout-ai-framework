package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Injects a configuration value into a field, converting it to the field's type. The expression
 * supports {@code ${...}} placeholders resolved against configuration, system properties and
 * environment variables, with an optional default ({@code ${key:default}}).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Value {

    /** The value expression, e.g. {@code ${anthropic.api.key}} or {@code ${port:8080}}. */
    String value() default "";
}
