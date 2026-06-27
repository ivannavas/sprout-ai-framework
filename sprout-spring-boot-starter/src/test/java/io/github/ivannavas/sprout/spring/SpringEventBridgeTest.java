package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.event.Event;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringEventBridgeTest {

    record SampleEvent(String payload, Instant occurredAt) implements Event {
        SampleEvent(String payload) {
            this(payload, Instant.now());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SproutAutoConfiguration.class))
            .withPropertyValues("sprout.scan.base-packages=io.github.ivannavas.sprout.spring.sample")
            .withUserConfiguration(SpringListener.class);

    @Test
    void sproutEventReachesSpringListener() {
        runner.run(context -> {
            SpringListener listener = context.getBean(SpringListener.class);
            AbstractEventBus bus = context.getBean(AbstractEventBus.class);

            SampleEvent event = new SampleEvent("from-sprout");
            bus.publish(event);

            assertThat(listener.received).containsExactly(event);
        });
    }

    @Test
    void springEventReachesSproutSubscriber() {
        runner.run(context -> {
            AbstractEventBus bus = context.getBean(AbstractEventBus.class);

            List<Event> onBus = new ArrayList<>();
            bus.subscribe(Event.class, onBus::add);

            SampleEvent event = new SampleEvent("from-spring");
            context.publishEvent(event);

            // Exactly once: the bridge forwards it to the bus, and the echo back to Spring is suppressed.
            assertThat(onBus).containsExactly(event);
        });
    }

    @Test
    void eachSideDeliversExactlyOnceWithoutLooping() {
        runner.run(context -> {
            SpringListener springListener = context.getBean(SpringListener.class);
            AbstractEventBus bus = context.getBean(AbstractEventBus.class);

            List<Event> onBus = new ArrayList<>();
            bus.subscribe(Event.class, onBus::add);

            // Publish on the bus: the Sprout subscriber and the Spring listener each see it once.
            bus.publish(new SampleEvent("once"));
            assertThat(onBus).hasSize(1);
            assertThat(springListener.received).hasSize(1);

            // And publishing through Spring also lands on both sides exactly once.
            context.publishEvent(new SampleEvent("twice"));
            assertThat(onBus).hasSize(2);
            assertThat(springListener.received).hasSize(2);
        });
    }

    @Configuration
    static class SpringListener {
        final List<Event> received = new ArrayList<>();

        @EventListener
        void onSampleEvent(SampleEvent event) {
            received.add(event);
        }
    }
}
