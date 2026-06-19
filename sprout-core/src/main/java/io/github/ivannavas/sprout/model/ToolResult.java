package io.github.ivannavas.sprout.model;

/** The result of a tool invocation, correlated to its {@link ToolCall} by {@code toolCallId}. */
public record ToolResult(String toolCallId, String content, boolean error) {

    public static ToolResult ok(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false);
    }

    public static ToolResult failure(String toolCallId, String errorMessage) {
        return new ToolResult(toolCallId, errorMessage, true);
    }
}
