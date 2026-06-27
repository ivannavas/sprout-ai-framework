package io.github.ivannavas.sprout.event;

import io.github.ivannavas.sprout.model.ModelResponse;

import java.time.Instant;

/**
 * Published by a {@link io.github.ivannavas.sprout.executor.ModelExecutor} after the underlying model
 * returns, carrying the {@link ModelResponse} (whose usage and finish reason describe that single
 * call). Fires for every execution, inside an agent or standalone. {@code modelName} is the executor's
 * simple class name.
 */
public record ModelResponseEvent(
        String modelName,
        ModelResponse response,
        Instant occurredAt
) implements Event {

    public ModelResponseEvent(String modelName, ModelResponse response) {
        this(modelName, response, Instant.now());
    }
}
