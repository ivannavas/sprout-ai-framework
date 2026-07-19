package io.github.ivannavas.sprout.monitoring;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.event.AgentCompletedEvent;
import io.github.ivannavas.sprout.event.AgentFailedEvent;
import io.github.ivannavas.sprout.event.ModelResponseEvent;
import io.github.ivannavas.sprout.event.ToolCalledEvent;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;
import io.github.ivannavas.sprout.monitoring.pricing.PricingTable;

/**
 * Subscribes to the prefab agent/model events and folds each one into the {@link AbstractUsageStore},
 * pricing model calls through the {@link PricingTable}. This is the bridge that turns the observability
 * stream the event bus already publishes into the usage, token and cost totals the store accumulates —
 * so monitoring adds no work to the agent loop beyond the events it was already emitting.
 */
public class UsageCollector {

    private final AbstractUsageStore store;
    private final PricingTable pricing;

    public UsageCollector(AbstractUsageStore store, PricingTable pricing) {
        this.store = store;
        this.pricing = pricing;
    }

    /** Subscribes this collector to every event it records. Call once, after the bus exists. */
    public void subscribe(AbstractEventBus bus) {
        bus.subscribe(ModelResponseEvent.class, this::onModelResponse);
        bus.subscribe(AgentCompletedEvent.class, this::onAgentCompleted);
        bus.subscribe(AgentFailedEvent.class, this::onAgentFailed);
        bus.subscribe(ToolCalledEvent.class, this::onToolCalled);
    }

    private void onModelResponse(ModelResponseEvent event) {
        TokenUsage usage = event.response().usage();
        // Cost comes from the full usage so cached input is billed at its own rate, while the recorded
        // token count is the whole prompt: with caching on, inputTokens() alone is just the uncached
        // remainder and would make prompts look like they shrank.
        double cost = pricing.costOf(event.modelName(), usage);
        store.recordModelCall(new ModelCall(
                event.modelName(), usage.totalInputTokens(), usage.outputTokens(), cost, event.occurredAt()));
    }

    private void onAgentCompleted(AgentCompletedEvent event) {
        AgentResult result = event.result();
        store.recordAgentRun(new AgentRun(event.agentName(), true, result.iterations(),
                result.totalUsage().totalInputTokens(), result.totalUsage().outputTokens(), event.occurredAt()));
    }

    private void onAgentFailed(AgentFailedEvent event) {
        // A failed run carries no AgentResult, so iterations and tokens are unknown (recorded as zero).
        store.recordAgentRun(new AgentRun(event.agentName(), false, 0, 0, 0, event.occurredAt()));
    }

    private void onToolCalled(ToolCalledEvent event) {
        store.recordToolCall(new ToolInvocation(event.agentName(), event.call().name(), event.occurredAt()));
    }
}
