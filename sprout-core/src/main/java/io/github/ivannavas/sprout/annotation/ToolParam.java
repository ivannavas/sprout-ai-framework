package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a {@link Tool @Tool} method parameter in the JSON-Schema advertised to the model. It is
 * optional — a parameter without it is required and carries no description — but a good description
 * markedly improves how reliably the model fills the argument in.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ToolParam {

    /** Human-readable description of the parameter, shown to the model in the tool's schema. */
    String description() default "";

    /** Whether the model must supply this parameter. Defaults to {@code true}. */
    boolean required() default true;
}
