package io.github.ivannavas.sprout.example.events;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.event.AgentCompletedEvent;
import io.github.ivannavas.sprout.event.AgentStartedEvent;
import io.github.ivannavas.sprout.event.Event;
import io.github.ivannavas.sprout.event.ModelResponseEvent;
import io.github.ivannavas.sprout.event.ToolCalledEvent;
import io.github.ivannavas.sprout.executor.AgentExecutor;

/**
 * Observes an agent run through the event bus, fully offline. The container auto-registers the default
 * {@code InMemoryEventBus}; here we subscribe a single catch-all listener (subscribing to
 * {@link Event} receives every published event), run the {@link WeatherAgent}, and print each prefab
 * lifecycle event as it arrives — the model request/response turns, the tool the model called, and the
 * run's start/finish. Swap in a Redis- or Kafka-backed {@code @EventBus} and the same listener would
 * fire on events published from other processes.
 */
public final class EventsExampleApplication {

    public static void main(String[] args) {
        SproutContainer container = SproutApplication.run(EventsExampleApplication.class);
        AbstractEventBus events = container.eventBus();

        // One subscriber on Event.class sees the whole stream, in the order the agent loop publishes it.
        events.subscribe(Event.class, EventsExampleApplication::describe);

        AgentExecutor weather = container.getSingleton("weatherAgentExecutor");

        System.out.println("== Events: observing an agent run through the bus ==");
        String answer = weather.execute("session", "What's the weather in Madrid?").response();
        System.out.println("Answer: " + answer);
    }

    private static void describe(Event event) {
        String detail = switch (event) {
            case AgentStartedEvent e -> e.agentName() + " started: \"" + e.prompt() + "\"";
            case ModelResponseEvent e -> e.modelName() + " produced "
                    + e.response().usage().outputTokens() + " output tokens";
            case ToolCalledEvent e -> "tool " + e.call().name() + " returned " + e.result().content();
            case AgentCompletedEvent e -> "finished in " + e.result().iterations() + " iteration(s)";
            default -> "";
        };
        System.out.println("  " + event.getClass().getSimpleName() + (detail.isEmpty() ? "" : " -> " + detail));
    }
}
