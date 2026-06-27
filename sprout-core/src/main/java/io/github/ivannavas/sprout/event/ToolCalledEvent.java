package io.github.ivannavas.sprout.event;

import io.github.ivannavas.sprout.model.ToolCall;
import io.github.ivannavas.sprout.model.ToolResult;

import java.time.Instant;

/**
 * Published by the agent loop after it dispatches a tool the model requested, pairing the originating
 * {@link ToolCall} with its {@link ToolResult} (which may be a failure).
 */
public record ToolCalledEvent(
        String agentName,
        String conversationId,
        ToolCall call,
        ToolResult result,
        Instant occurredAt
) implements Event {

    public ToolCalledEvent(String agentName, String conversationId, ToolCall call, ToolResult result) {
        this(agentName, conversationId, call, result, Instant.now());
    }
}
