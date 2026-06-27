package io.github.ivannavas.sprout.monitoring.itest;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/** A tiny agent whose single {@code ping} tool the stub model calls, so a tool invocation is recorded. */
@Agent(model = StubModel.class, systemPrompt = "You are a test agent.")
public class MonitoredAgent extends AgentExecutor {

    @Tool(description = "Replies with pong")
    public String ping(String value) {
        return "pong " + value;
    }
}
