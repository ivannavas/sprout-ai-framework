package io.github.ivannavas.sprout.mcp.itest;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.mcp.annotation.Mcp;

/** A class that is both a Sprout {@code @Agent} and an {@code @Mcp} tool provider. */
@Agent(model = EchoModel.class, systemPrompt = "test")
@Mcp(name = "combined", version = "9.9")
public class CombinedAgent extends AgentExecutor {

    @Tool(name = "ping", description = "Replies pong")
    public String ping() {
        return "pong";
    }
}
