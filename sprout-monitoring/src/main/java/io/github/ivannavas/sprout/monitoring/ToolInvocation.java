package io.github.ivannavas.sprout.monitoring;

import java.time.Instant;

/** A single tool dispatch recorded into the usage store: the agent that called it and the tool name. */
public record ToolInvocation(String agentName, String toolName, Instant at) {
}
