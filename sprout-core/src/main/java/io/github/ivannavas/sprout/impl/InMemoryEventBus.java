package io.github.ivannavas.sprout.impl;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.annotation.EventBus;
import io.github.ivannavas.sprout.event.Event;
import io.github.ivannavas.sprout.event.EventListener;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Default {@link AbstractEventBus}: delivers events synchronously, in the publishing thread, to
 * in-process listeners. Subscription and publication are safe for concurrent use; events do not cross
 * process boundaries — swap in a distributed {@code @EventBus} for that. A listener that throws is
 * logged and skipped so one bad subscriber cannot abort the publish (or the agent run that triggered it).
 */
@EventBus
public class InMemoryEventBus implements AbstractEventBus {

    private static final Logger logger = Logger.getLogger(InMemoryEventBus.class.getName());

    // Listeners keyed by the type they subscribed to; a published event is matched against every key
    // that is assignable from its class, so subscribing to a supertype catches all subtypes.
    private final Map<Class<?>, List<EventListener<?>>> listenersByType = new ConcurrentHashMap<>();

    @Override
    public void publish(Event event) {
        for (Map.Entry<Class<?>, List<EventListener<?>>> entry : listenersByType.entrySet()) {
            if (!entry.getKey().isAssignableFrom(event.getClass())) {
                continue;
            }
            for (EventListener<?> listener : entry.getValue()) {
                notifyListener(listener, event);
            }
        }
    }

    @Override
    public <E extends Event> void subscribe(Class<E> eventType, EventListener<E> listener) {
        listenersByType.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public <E extends Event> void unsubscribe(Class<E> eventType, EventListener<E> listener) {
        List<EventListener<?>> listeners = listenersByType.get(eventType);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    @SuppressWarnings("unchecked")
    private void notifyListener(EventListener<?> listener, Event event) {
        try {
            ((EventListener<Event>) listener).onEvent(event);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Sprout: event listener failed handling " + event.getClass().getSimpleName(), e);
        }
    }
}
