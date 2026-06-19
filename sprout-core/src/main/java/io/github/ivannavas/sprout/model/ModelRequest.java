package io.github.ivannavas.sprout.model;

import java.util.List;

/** A request to a model: the conversation so far and the tools the model may call (possibly none). */
public record ModelRequest(List<Message> messages, List<ToolDefinition> tools) {

    public ModelRequest(List<Message> messages) {
        this(messages, List.of());
    }
}
