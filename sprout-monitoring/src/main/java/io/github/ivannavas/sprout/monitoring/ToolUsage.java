package io.github.ivannavas.sprout.monitoring;

/** Aggregated usage for one tool: how many times the agents called it. */
public record ToolUsage(String toolName, long calls) {

    /** Combines two aggregates for the same tool. */
    public ToolUsage plus(ToolUsage other) {
        return new ToolUsage(toolName, calls + other.calls);
    }
}
