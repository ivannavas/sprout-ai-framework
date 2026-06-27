package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.event.Event;
import io.github.ivannavas.sprout.event.EventListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Bridges Sprout's {@link AbstractEventBus} and Spring's application-event system in both directions, so
 * the two halves of an app can react to each other's events without knowing which side raised them:
 *
 * <ul>
 *   <li><b>Sprout → Spring:</b> every {@link Event} published on the bus (the prefab agent/model
 *       lifecycle events, or your own) is republished through Spring's
 *       {@link ApplicationEventPublisher}, so a {@code @EventListener} can handle it like any Spring
 *       event.</li>
 *   <li><b>Spring → Sprout:</b> any Spring event whose payload is a Sprout {@link Event}
 *       (i.e. {@code applicationEventPublisher.publishEvent(mySproutEvent)}) is forwarded onto the bus,
 *       reaching every Sprout subscriber.</li>
 * </ul>
 *
 * <p>A forwarded event would otherwise be delivered back to the bridge and bounced to the other side
 * forever; an identity-based, per-thread guard suppresses that echo. This relies on Spring's default
 * synchronous event delivery — configuring an asynchronous multicaster would defeat the guard.
 */
public class SpringEventBridge implements ApplicationListener<PayloadApplicationEvent<?>>, DisposableBean {

    private final AbstractEventBus eventBus;
    private final ApplicationEventPublisher publisher;
    private final EventListener<Event> sproutListener = this::forwardToSpring;

    // Events currently crossing the bridge on this thread, tracked by identity (not value) so a record
    // event forwarded one way is not bounced back when it arrives on the other side. Delivery is
    // synchronous and in-thread on both sides, so a ThreadLocal is enough to break the echo.
    private final ThreadLocal<Set<Event>> crossing =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    public SpringEventBridge(AbstractEventBus eventBus, ApplicationEventPublisher publisher) {
        this.eventBus = eventBus;
        this.publisher = publisher;
        eventBus.subscribe(Event.class, sproutListener);
    }

    private void forwardToSpring(Event event) {
        bridge(event, () -> publisher.publishEvent(event));
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<?> event) {
        if (event.getPayload() instanceof Event sproutEvent) {
            bridge(sproutEvent, () -> eventBus.publish(sproutEvent));
        }
    }

    // Runs `forward` unless `event` is already crossing the bridge on this thread — which means it
    // originated on the far side and is only being echoed back, so forwarding again would loop.
    private void bridge(Event event, Runnable forward) {
        Set<Event> inFlight = crossing.get();
        if (!inFlight.add(event)) {
            return;
        }
        try {
            forward.run();
        } finally {
            inFlight.remove(event);
            if (inFlight.isEmpty()) {
                crossing.remove();
            }
        }
    }

    @Override
    public void destroy() {
        eventBus.unsubscribe(Event.class, sproutListener);
    }
}
