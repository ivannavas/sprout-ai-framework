package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a property within a {@link Configuration} class. On a field, the property's value is injected
 * (converted to the field type). On a method, the method's return value supplies a default for the
 * property when it is not otherwise configured.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ConfigurationProperty {

    /** Property key. Defaults to the field or method name. */
    String value() default "";
}
