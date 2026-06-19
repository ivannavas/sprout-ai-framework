package io.github.ivannavas.sprout.example.mcp.agent;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.model.AgentResult;

/**
 * Runs the {@link MathAgent}. On the agent's first turn its {@code @UseMcp} connection launches the
 * MCP server process and lists its tools; the stub model then asks for the {@code multiply} tool,
 * which the executor dispatches to the server, and prints the final answer.
 */
public final class McpAgentApplication {

    public static void main(String[] args) {
        SproutContainer container = SproutApplication.run(McpAgentApplication.class);

        AgentExecutor agent = container.getSingleton("mathAgentExecutor");
        AgentResult result = agent.execute("demo-session", "What is 6 times 7?");

        System.out.println("Agent answer: " + result.response());
    }
}
