package io.github.ivannavas.sprout.mcp.annotation;

import io.github.ivannavas.sprout.annotation.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class whose {@link io.github.ivannavas.sprout.annotation.Tool @Tool} methods should be
 * exposed through a Model Context Protocol (MCP) server.
 *
 * <p>Because it is itself a {@link Component}, the annotated class is managed by the Sprout
 * container like any other bean: standalone, it is wired by the default component processor; placed
 * alongside {@link io.github.ivannavas.sprout.annotation.Agent @Agent}, the agent processor still
 * builds the agent. In both cases the same instance's {@code @Tool} methods are discovered and
 * served by {@link io.github.ivannavas.sprout.mcp.McpServer}.
 *
 * <p>Every {@code @Mcp} bean in the container contributes its tools to a single aggregated server.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Mcp {

    /** Logical server name advertised to MCP clients during {@code initialize}. */
    String name() default "sprout-mcp";

    /** Server version advertised to MCP clients during {@code initialize}. */
    String version() default "1.0.0";
}
