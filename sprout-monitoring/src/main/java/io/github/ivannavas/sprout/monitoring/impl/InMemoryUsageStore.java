package io.github.ivannavas.sprout.monitoring.impl;

import io.github.ivannavas.sprout.monitoring.AgentRun;
import io.github.ivannavas.sprout.monitoring.AgentUsage;
import io.github.ivannavas.sprout.monitoring.ModelCall;
import io.github.ivannavas.sprout.monitoring.ModelUsage;
import io.github.ivannavas.sprout.monitoring.ToolInvocation;
import io.github.ivannavas.sprout.monitoring.ToolUsage;
import io.github.ivannavas.sprout.monitoring.UsageSnapshot;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;
import io.github.ivannavas.sprout.monitoring.annotation.UsageStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link AbstractUsageStore}: keeps running totals in memory, broken down by model, agent and
 * tool. Each record folds into the matching aggregate with {@link Map#merge}, which is atomic per key on
 * the {@link ConcurrentHashMap}, so concurrent recording from parallel agent runs is safe. Totals live for
 * the lifetime of the process — swap in a persistent {@code @UsageStore} to keep them across restarts.
 */
@UsageStore
public class InMemoryUsageStore implements AbstractUsageStore {

    private final Map<String, ModelUsage> byModel = new ConcurrentHashMap<>();
    private final Map<String, AgentUsage> byAgent = new ConcurrentHashMap<>();
    private final Map<String, ToolUsage> byTool = new ConcurrentHashMap<>();

    @Override
    public void recordModelCall(ModelCall call) {
        byModel.merge(call.modelName(),
                new ModelUsage(call.modelName(), 1, call.inputTokens(), call.outputTokens(), call.cost()),
                ModelUsage::plus);
    }

    @Override
    public void recordAgentRun(AgentRun run) {
        byAgent.merge(run.agentName(),
                new AgentUsage(run.agentName(), run.success() ? 1 : 0, run.success() ? 0 : 1,
                        run.iterations(), run.inputTokens(), run.outputTokens()),
                AgentUsage::plus);
    }

    @Override
    public void recordToolCall(ToolInvocation invocation) {
        byTool.merge(invocation.toolName(), new ToolUsage(invocation.toolName(), 1), ToolUsage::plus);
    }

    @Override
    public UsageSnapshot snapshot() {
        long calls = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        double totalCost = 0;
        // Global token and cost totals come solely from the model calls so each execution is counted once,
        // whether it ran inside an agent or standalone.
        for (ModelUsage usage : byModel.values()) {
            calls += usage.calls();
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            totalCost += usage.cost();
        }
        return new UsageSnapshot(calls, inputTokens, outputTokens, totalCost,
                Map.copyOf(byModel), Map.copyOf(byAgent), Map.copyOf(byTool));
    }
}
