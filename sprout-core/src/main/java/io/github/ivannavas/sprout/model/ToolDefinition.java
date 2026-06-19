package io.github.ivannavas.sprout.model;

/** A tool advertised to the model: its name, description and JSON-Schema for its parameters. */
public record ToolDefinition(String name, String description, String parametersJson) {
}
