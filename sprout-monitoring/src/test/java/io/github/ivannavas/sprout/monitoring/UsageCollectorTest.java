package io.github.ivannavas.sprout.monitoring;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.event.AgentCompletedEvent;
import io.github.ivannavas.sprout.event.AgentFailedEvent;
import io.github.ivannavas.sprout.event.ModelResponseEvent;
import io.github.ivannavas.sprout.event.ToolCalledEvent;
import io.github.ivannavas.sprout.impl.InMemoryEventBus;
import io.github.ivannavas.sprout.model.AgentResult;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolResult;
import io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore;
import io.github.ivannavas.sprout.monitoring.pricing.PricingTable;
import io.github.ivannavas.sprout.monitoring.pricing.PricingTable.Rate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsageCollectorTest {

    private final AbstractEventBus bus = new InMemoryEventBus();
    private final InMemoryUsageStore store = new InMemoryUsageStore();

    private void wire(PricingTable pricing) {
        new UsageCollector(store, pricing).subscribe(bus);
    }

    @Test
    void modelResponseEventsBecomePricedModelCalls() {
        PricingTable pricing = new PricingTable();
        pricing.put("StubModel", new Rate(3.0, 6.0));
        wire(pricing);

        bus.publish(new ModelResponseEvent("StubModel",
                new ModelResponse(Message.assistant("hi"),
                        new TokenUsage(1_000_000, 1_000_000), FinishReason.STOP)));

        UsageSnapshot snapshot = store.snapshot();
        assertEquals(1, snapshot.modelCalls());
        assertEquals(2_000_000, snapshot.totalTokens());
        assertEquals(9.0, snapshot.totalCost(), 1e-9);
        assertEquals(9.0, snapshot.byModel().get("StubModel").cost(), 1e-9);
    }

    @Test
    void agentAndToolEventsAreRecorded() {
        wire(new PricingTable());

        bus.publish(new AgentCompletedEvent("Agent", "c1",
                new AgentResult("c1", "answer", 2, new TokenUsage(100, 50))));
        bus.publish(new AgentFailedEvent("Agent", "c2", new RuntimeException("boom")));
        bus.publish(new ToolCalledEvent("Agent", "c1",
                new ToolCall("id", "forecast", "{}"), ToolResult.ok("id", "ok")));

        UsageSnapshot snapshot = store.snapshot();
        AgentUsage agent = snapshot.byAgent().get("Agent");
        assertEquals(2, agent.runs());
        assertEquals(1, agent.completed());
        assertEquals(1, agent.failed());
        assertEquals(2, agent.iterations());
        assertEquals(150, agent.totalTokens());
        assertEquals(1, snapshot.byTool().get("forecast").calls());
    }
}
