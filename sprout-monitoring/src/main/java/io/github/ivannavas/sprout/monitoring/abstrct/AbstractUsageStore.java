package io.github.ivannavas.sprout.monitoring.abstrct;

import io.github.ivannavas.sprout.monitoring.AgentRun;
import io.github.ivannavas.sprout.monitoring.ModelCall;
import io.github.ivannavas.sprout.monitoring.ToolInvocation;
import io.github.ivannavas.sprout.monitoring.UsageSnapshot;

/**
 * Stores the usage the {@link io.github.ivannavas.sprout.monitoring.UsageCollector collector} derives from
 * the event bus — model calls, agent runs and tool invocations — and exposes it as a {@link UsageSnapshot}.
 * Implement it (and annotate with {@link io.github.ivannavas.sprout.monitoring.annotation.UsageStore
 * @UsageStore}) to back monitoring with a custom sink such as a database or a metrics backend;
 * {@link io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore} is the default.
 *
 * <p>The {@code record*} methods are called from the event bus thread, possibly concurrently when agents
 * run in parallel, so implementations must be safe for concurrent use.
 */
public interface AbstractUsageStore {

    /** Records one model execution and its derived cost. */
    void recordModelCall(ModelCall call);

    /** Records one finished agent run (completed or failed). */
    void recordAgentRun(AgentRun run);

    /** Records one tool dispatch. */
    void recordToolCall(ToolInvocation invocation);

    /**
     * Returns the usage accumulated so far. A sink that only forwards metrics elsewhere (rather than
     * aggregating) may return an empty snapshot.
     */
    UsageSnapshot snapshot();
}
