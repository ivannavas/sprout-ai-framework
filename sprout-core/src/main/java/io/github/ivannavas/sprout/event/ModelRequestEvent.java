package io.github.ivannavas.sprout.event;

import io.github.ivannavas.sprout.model.ModelRequest;

import java.time.Instant;

/**
 * Published by a {@link io.github.ivannavas.sprout.executor.ModelExecutor} right before it calls the
 * underlying model — whether the call comes from an agent's loop or from invoking the model directly.
 * {@code modelName} is the executor's simple class name.
 */
public record ModelRequestEvent(
        String modelName,
        ModelRequest request,
        Instant occurredAt
) implements Event {

    public ModelRequestEvent(String modelName, ModelRequest request) {
        this(modelName, request, Instant.now());
    }
}
