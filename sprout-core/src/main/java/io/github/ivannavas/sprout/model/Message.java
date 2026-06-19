package io.github.ivannavas.sprout.model;

import java.util.List;

/**
 * A single message in a conversation. Depending on {@link Role}, it carries text {@code content},
 * the {@code toolCalls} an assistant requested, or the {@code toolResult} of a tool turn. Use the
 * static factory methods rather than the canonical constructor.
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, ToolResult toolResult) {

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, List.of(), null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, List.of(), null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, List.of(), null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, toolCalls, null);
    }

    public static Message tool(ToolResult result) {
        return new Message(Role.TOOL, null, List.of(), result);
    }

    public boolean requestsTools() {
        return role == Role.ASSISTANT && toolCalls != null && !toolCalls.isEmpty();
    }
}
