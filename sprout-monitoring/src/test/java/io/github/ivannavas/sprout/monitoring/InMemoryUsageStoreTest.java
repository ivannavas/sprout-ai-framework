package io.github.ivannavas.sprout.monitoring;

import io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryUsageStoreTest {

    private final InMemoryUsageStore store = new InMemoryUsageStore();
    private final Instant now = Instant.now();

    @Test
    void aggregatesModelCallsByModelAndGlobally() {
        store.recordModelCall(new ModelCall("ModelA", 10, 5, 0.10, now));
        store.recordModelCall(new ModelCall("ModelA", 20, 7, 0.20, now));
        store.recordModelCall(new ModelCall("ModelB", 1, 1, 0.01, now));

        UsageSnapshot snapshot = store.snapshot();
        assertEquals(3, snapshot.modelCalls());
        assertEquals(31, snapshot.inputTokens());
        assertEquals(13, snapshot.outputTokens());
        assertEquals(44, snapshot.totalTokens());
        assertEquals(0.31, snapshot.totalCost(), 1e-9);

        ModelUsage a = snapshot.byModel().get("ModelA");
        assertEquals(2, a.calls());
        assertEquals(30, a.inputTokens());
        assertEquals(12, a.outputTokens());
        assertEquals(0.30, a.cost(), 1e-9);
    }

    @Test
    void countsCompletedAndFailedRunsPerAgent() {
        store.recordAgentRun(new AgentRun("Agent", true, 3, 100, 50, now));
        store.recordAgentRun(new AgentRun("Agent", true, 2, 40, 20, now));
        store.recordAgentRun(new AgentRun("Agent", false, 0, 0, 0, now));

        AgentUsage agent = store.snapshot().byAgent().get("Agent");
        assertEquals(3, agent.runs());
        assertEquals(2, agent.completed());
        assertEquals(1, agent.failed());
        assertEquals(5, agent.iterations());
        assertEquals(210, agent.totalTokens());
    }

    @Test
    void countsToolCallsByName() {
        store.recordToolCall(new ToolInvocation("Agent", "search", now));
        store.recordToolCall(new ToolInvocation("Agent", "search", now));
        store.recordToolCall(new ToolInvocation("Agent", "weather", now));

        UsageSnapshot snapshot = store.snapshot();
        assertEquals(2, snapshot.byTool().get("search").calls());
        assertEquals(1, snapshot.byTool().get("weather").calls());
    }

    @Test
    void emptyStoreYieldsZeroedSnapshot() {
        UsageSnapshot snapshot = store.snapshot();
        assertEquals(0, snapshot.modelCalls());
        assertEquals(0, snapshot.totalTokens());
        assertEquals(0.0, snapshot.totalCost(), 1e-9);
        assertTrue(snapshot.byModel().isEmpty());
        assertTrue(snapshot.byAgent().isEmpty());
        assertTrue(snapshot.byTool().isEmpty());
    }
}
