package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exposes a method as a tool the model can call. A JSON-Schema for the method's parameters is
 * generated automatically from their names and types, so compile with {@code -parameters}; enums,
 * arrays and collections are mapped too. Annotate a parameter with {@link ToolParam} to give it a
 * description or mark it optional. Used on {@link Agent @Agent} methods (callable by that agent's
 * model) and on {@code @Mcp} beans (published to MCP clients).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    /** Tool name advertised to the model. Defaults to the method name. */
    String name()  default "";

    /** Human-readable description that tells the model when to use the tool. */
    String description() default "";
}
