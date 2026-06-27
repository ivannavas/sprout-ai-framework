package io.github.ivannavas.sprout.abstrct;

import io.github.ivannavas.sprout.event.Event;
import io.github.ivannavas.sprout.event.EventListener;

/**
 * Publishes {@link Event}s to interested {@link EventListener}s, decoupling the code that emits an event
 * from the code that reacts to it. The agent loop publishes the prefab lifecycle events (see the
 * {@link io.github.ivannavas.sprout.event} package) here, and application code can publish its own.
 *
 * <p>{@link io.github.ivannavas.sprout.impl.InMemoryEventBus} is the default, in-process implementation.
 * Implement this interface (and annotate with {@link io.github.ivannavas.sprout.annotation.EventBus @EventBus})
 * to back the bus with something distributed instead — Redis pub/sub, Kafka, a message broker — and that
 * bean replaces the default everywhere it is injected.
 */
public interface AbstractEventBus {

    /** Delivers {@code event} to every listener subscribed to its type or one of its supertypes. */
    void publish(Event event);

    /**
     * Registers {@code listener} to receive published events that are instances of {@code eventType}.
     * Subscribing to a supertype (e.g. {@link Event}) receives every matching subtype.
     */
    <E extends Event> void subscribe(Class<E> eventType, EventListener<E> listener);

    /** Removes a previously {@link #subscribe subscribed} listener; a no-op if it was never registered. */
    <E extends Event> void unsubscribe(Class<E> eventType, EventListener<E> listener);
}
