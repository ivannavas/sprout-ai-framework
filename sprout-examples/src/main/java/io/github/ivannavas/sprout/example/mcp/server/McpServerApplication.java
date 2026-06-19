package io.github.ivannavas.sprout.example.mcp.server;

import io.github.ivannavas.sprout.SproutApplication;

/**
 * Runs the MCP server: bootstrapping the container discovers {@link MathTools} and
 * {@code McpProcessor} serves its tools over stdio automatically. This is the process an MCP client
 * (such as {@code McpAgentApplication}) launches and talks to.
 */
public final class McpServerApplication {

    public static void main(String[] args) {
        SproutApplication.run(McpServerApplication.class);
    }
}
