package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link io.github.ivannavas.sprout.executor.ModelExecutor} implementation as a
 * model. The annotated executor is the model: it is instantiated and wired by the container,
 * then registered for injection. By default it is registered under the conventional bean name
 * (the class name in camelCase); set {@link #value()} to add an extra logical name.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Model {
    String value() default "";
}
