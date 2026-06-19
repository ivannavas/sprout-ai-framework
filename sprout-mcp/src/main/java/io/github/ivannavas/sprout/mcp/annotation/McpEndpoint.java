package io.github.ivannavas.sprout.mcp.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes one MCP server an agent connects to. Used inside {@link UseMcp}.
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface McpEndpoint {

    /**
     * The command (and arguments) that launches the server as a child process speaking MCP over
     * stdio. Each token may contain {@code ${...}} placeholders resolved against the container's
     * configuration/system properties (e.g. {@code "${java.home}/bin/java"}).
     */
    String[] command();

    /** Optional logical name for the connection, used in logs and errors. */
    String name() default "";
}
