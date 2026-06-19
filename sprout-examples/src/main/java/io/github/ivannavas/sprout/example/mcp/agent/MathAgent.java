package io.github.ivannavas.sprout.example.mcp.agent;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.mcp.annotation.McpEndpoint;
import io.github.ivannavas.sprout.mcp.annotation.UseMcp;

/**
 * An agent with no {@code @Tool} methods of its own: all its tools come from the MCP server it
 * connects to. The {@code @UseMcp} command launches that server ({@code McpServerApplication}) in a
 * fresh JVM reusing this process's java executable and classpath, then speaks MCP over its stdio.
 */
@Agent(
        model = StubChatModel.class,
        systemPrompt = "You are a calculator that uses MCP tools.",
        maxIterations = 4
)
@UseMcp(@McpEndpoint(
        name = "math",
        command = {
                "${java.home}/bin/java",
                "-cp", "${java.class.path}",
                "io.github.ivannavas.sprout.example.mcp.server.McpServerApplication"
        }))
public class MathAgent extends AgentExecutor {
}
