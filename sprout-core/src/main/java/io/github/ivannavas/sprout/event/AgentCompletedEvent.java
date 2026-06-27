package io.github.ivannavas.sprout.event;

import io.github.ivannavas.sprout.model.AgentResult;

import java.time.Instant;

/** Published when an agent finishes a run successfully, carrying the {@link AgentResult}. */
public record AgentCompletedEvent(
        String agentName,
        String conversationId,
        AgentResult result,
        Instant occurredAt
) implements Event {

    public AgentCompletedEvent(String agentName, String conversationId, AgentResult result) {
        this(agentName, conversationId, result, Instant.now());
    }
}
