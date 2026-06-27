package io.github.ivannavas.sprout.monitoring;

/**
 * Aggregated usage for one agent: how many runs completed versus failed, the total iterations they took
 * and the tokens they consumed. Token totals come from each run's {@code AgentResult}, so they are a
 * per-agent view; the global and per-model token totals in a {@link UsageSnapshot} are sourced from the
 * model calls instead, to avoid double-counting the same calls.
 */
public record AgentUsage(String agentName, long completed, long failed, long iterations,
                         long inputTokens, long outputTokens) {

    public long runs() {
        return completed + failed;
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /** Combines two aggregates for the same agent. */
    public AgentUsage plus(AgentUsage other) {
        return new AgentUsage(agentName,
                completed + other.completed,
                failed + other.failed,
                iterations + other.iterations,
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens);
    }
}
