package io.github.ivannavas.sprout.event;

/**
 * Receives events of type {@code E} that were published to an
 * {@link io.github.ivannavas.sprout.abstrct.AbstractEventBus}. Register one with
 * {@link io.github.ivannavas.sprout.abstrct.AbstractEventBus#subscribe(Class, EventListener)};
 * subscribing to a supertype (e.g. {@link Event} itself) receives every matching subtype.
 */
@FunctionalInterface
public interface EventListener<E extends Event> {

    /** Handles a published event. */
    void onEvent(E event);
}
