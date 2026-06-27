package io.github.ivannavas.sprout.event;

import java.time.Instant;

/**
 * Published when an agent run aborts with an exception (including exceeding {@code maxIterations}). The
 * exception is rethrown to the caller after the event is delivered.
 */
public record AgentFailedEvent(
        String agentName,
        String conversationId,
        Throwable error,
        Instant occurredAt
) implements Event {

    public AgentFailedEvent(String agentName, String conversationId, Throwable error) {
        this(agentName, conversationId, error, Instant.now());
    }
}
