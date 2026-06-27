package io.github.ivannavas.sprout.event;

import java.time.Instant;

/**
 * Published when an agent begins a run, before the first model call. {@code agentName} is the agent's
 * simple class name.
 */
public record AgentStartedEvent(
        String agentName,
        String conversationId,
        String prompt,
        Instant occurredAt
) implements Event {

    public AgentStartedEvent(String agentName, String conversationId, String prompt) {
        this(agentName, conversationId, prompt, Instant.now());
    }
}
