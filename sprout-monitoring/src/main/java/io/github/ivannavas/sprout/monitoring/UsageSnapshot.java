package io.github.ivannavas.sprout.monitoring;

import java.util.Map;

/**
 * An immutable point-in-time view of everything the usage store has recorded: global totals across all
 * model calls plus the per-model, per-agent and per-tool breakdowns. The global token and cost figures
 * are summed from the model calls, so they count every execution exactly once whether it ran inside an
 * agent or standalone.
 */
public record UsageSnapshot(long modelCalls, long inputTokens, long outputTokens, double totalCost,
                            Map<String, ModelUsage> byModel,
                            Map<String, AgentUsage> byAgent,
                            Map<String, ToolUsage> byTool) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }
}
