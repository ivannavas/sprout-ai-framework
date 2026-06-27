package io.github.ivannavas.sprout.event;

import java.time.Instant;

/**
 * Marker for anything that can travel through an {@link io.github.ivannavas.sprout.abstrct.AbstractEventBus}.
 * Sprout ships prefab events for the agent and model lifecycle (see this package), but an application or
 * module is free to define its own: any class that implements {@code Event} can be published and
 * subscribed to. Implementations should be immutable value objects — records are the natural fit.
 */
public interface Event {

    /** When the event occurred. */
    Instant occurredAt();
}
