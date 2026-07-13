package io.github.ivannavas.sprout.mcp.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Describes one MCP server an agent connects to. Used inside {@link UseMcp}. Set exactly one of
 * {@link #command()} (launch the server as a local child process over stdio) or {@link #url()}
 * (connect to an already-running server over HTTP).
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface McpEndpoint {

    /**
     * The command (and arguments) that launches the server as a child process speaking MCP over
     * stdio. Each token may contain {@code ${...}} placeholders resolved against the container's
     * configuration/system properties (e.g. {@code "${java.home}/bin/java"}). Leave empty when
     * connecting via {@link #url()} instead.
     */
    String[] command() default {};

    /**
     * The URL of an already-running MCP server to connect to over HTTP (the Streamable HTTP
     * transport). May contain {@code ${...}} placeholders resolved against the container's
     * configuration (e.g. {@code "${weather.mcp.url}"}). Leave empty when launching a local server
     * via {@link #command()} instead.
     */
    String url() default "";

    /** Optional logical name for the connection, used in logs and errors. */
    String name() default "";
}
