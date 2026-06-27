package io.github.ivannavas.sprout.monitoring.custom;

import io.github.ivannavas.sprout.monitoring.AgentRun;
import io.github.ivannavas.sprout.monitoring.ModelCall;
import io.github.ivannavas.sprout.monitoring.ToolInvocation;
import io.github.ivannavas.sprout.monitoring.UsageSnapshot;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;
import io.github.ivannavas.sprout.monitoring.annotation.UsageStore;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** A scanned {@code @UsageStore} that the initializer must prefer over the in-memory default. */
@UsageStore
public class CountingUsageStore implements AbstractUsageStore {

    final AtomicLong modelCalls = new AtomicLong();

    @Override
    public void recordModelCall(ModelCall call) {
        modelCalls.incrementAndGet();
    }

    @Override
    public void recordAgentRun(AgentRun run) {
    }

    @Override
    public void recordToolCall(ToolInvocation invocation) {
    }

    @Override
    public UsageSnapshot snapshot() {
        return new UsageSnapshot(modelCalls.get(), 0, 0, 0, Map.of(), Map.of(), Map.of());
    }
}
