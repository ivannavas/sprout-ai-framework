package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Selects an injected dependency by bean name instead of by type. Useful when several beans share a
 * type (e.g. multiple {@code @Model} executors) — {@code @Qualifier("anthropic")} picks one.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Qualifier {

    /** The target bean name. */
    String value();
}
