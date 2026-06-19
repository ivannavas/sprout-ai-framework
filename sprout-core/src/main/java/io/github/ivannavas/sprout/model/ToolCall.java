package io.github.ivannavas.sprout.model;

/** A model's request to invoke a tool: a correlation {@code id}, the tool name and JSON arguments. */
public record ToolCall(String id, String name, String argumentsJson) {
}
