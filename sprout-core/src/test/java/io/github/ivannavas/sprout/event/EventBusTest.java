package io.github.ivannavas.sprout.event;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.annotation.Tool;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.executor.ModelExecutor;
import io.github.ivannavas.sprout.impl.InMemoryConversationStore;
import io.github.ivannavas.sprout.impl.InMemoryEventBus;
import io.github.ivannavas.sprout.model.AgentData;
import io.github.ivannavas.sprout.model.FinishReason;
import io.github.ivannavas.sprout.model.Message;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.TokenUsage;
import io.github.ivannavas.sprout.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventBusTest {

    record CustomEvent(String payload, Instant occurredAt) implements Event {
        CustomEvent(String payload) {
            this(payload, Instant.now());
        }
    }

    @Test
    void deliversToSubscribersOfTheTypeAndItsSupertypes() {
        AbstractEventBus bus = new InMemoryEventBus();
        List<Event> all = new ArrayList<>();
        List<CustomEvent> custom = new ArrayList<>();

        bus.subscribe(Event.class, all::add);
        bus.subscribe(CustomEvent.class, custom::add);

        CustomEvent event = new CustomEvent("hello");
        bus.publish(event);

        assertEquals(1, custom.size(), "the exact-type subscriber receives it");
        assertSame(event, custom.get(0));
        assertEquals(1, all.size(), "subscribing to Event.class receives every subtype");
        assertSame(event, all.get(0));
    }

    @Test
    void unsubscribeStopsDelivery() {
        AbstractEventBus bus = new InMemoryEventBus();
        List<Event> received = new ArrayList<>();
        EventListener<CustomEvent> listener = received::add;

        bus.subscribe(CustomEvent.class, listener);
        bus.publish(new CustomEvent("first"));
        bus.unsubscribe(CustomEvent.class, listener);
        bus.publish(new CustomEvent("second"));

        assertEquals(1, received.size(), "no event should arrive after unsubscribe");
    }

    @Test
    void aThrowingListenerDoesNotAbortThePublish() {
        AbstractEventBus bus = new InMemoryEventBus();
        List<Event> received = new ArrayList<>();

        bus.subscribe(CustomEvent.class, e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe(CustomEvent.class, received::add);

        bus.publish(new CustomEvent("payload"));

        assertEquals(1, received.size(), "the healthy listener still runs");
    }

    /** An agent whose own {@code @Tool} method is invoked during the run. */
    static class EchoAgent extends AgentExecutor {
        @Tool(description = "Echoes the text back")
        public String echo(String text) {
            return text;
        }
    }

    /** Asks for the {@code echo} tool on the first turn, then answers. */
    static class ToolThenAnswerModel extends ModelExecutor {
        @Override
        public ModelResponse chat(ModelRequest request) {
            boolean toolDone = request.messages().stream().anyMatch(m -> m.toolResult() != null);
            if (!toolDone) {
                ToolCall call = new ToolCall("c1", "echo", "{\"text\":\"hi\"}");
                return new ModelResponse(Message.assistant(null, List.of(call)), TokenUsage.ZERO, FinishReason.TOOL_CALLS);
            }
            return new ModelResponse(Message.assistant("done"), TokenUsage.ZERO, FinishReason.STOP);
        }
    }

    @Test
    void agentPublishesItsLifecycleEvents() throws Exception {
        Method echo = EchoAgent.class.getMethod("echo", String.class);
        ToolThenAnswerModel model = new ToolThenAnswerModel();
        EchoAgent agent = new EchoAgent();
        agent.configure(new AgentData(model, new InMemoryConversationStore(), null, "", 5, Map.of("echo", echo)));

        AbstractEventBus bus = new InMemoryEventBus();
        List<Event> events = new ArrayList<>();
        bus.subscribe(Event.class, events::add);
        // The agent emits its own lifecycle events; the model emits the request/response ones, so both
        // are wired to the same bus, as the container does at startup.
        agent.setEventBus(bus);
        model.setEventBus(bus);

        agent.execute("session", "echo hi");

        assertInstanceOf(AgentStartedEvent.class, events.get(0), "the run starts with AgentStartedEvent");
        assertInstanceOf(AgentCompletedEvent.class, events.get(events.size() - 1), "and ends with AgentCompletedEvent");
        assertTrue(events.stream().anyMatch(e -> e instanceof ModelRequestEvent), "each turn emits a request event");
        assertTrue(events.stream().anyMatch(e -> e instanceof ModelResponseEvent), "and a response event");

        ToolCalledEvent toolEvent = events.stream()
                .filter(e -> e instanceof ToolCalledEvent)
                .map(e -> (ToolCalledEvent) e)
                .findFirst().orElseThrow();
        assertEquals("echo", toolEvent.call().name());
        assertEquals("EchoAgent", toolEvent.agentName());
    }

    @Test
    void modelInvokeEmitsRequestAndResponseEventsWithNoAgent() {
        AbstractEventBus bus = new InMemoryEventBus();
        List<Event> events = new ArrayList<>();
        bus.subscribe(Event.class, events::add);

        // No agent in the picture: invoking the model directly emits the model events.
        ToolThenAnswerModel model = new ToolThenAnswerModel();
        model.setEventBus(bus);
        model.invoke(new ModelRequest(List.of(Message.user("hi")), List.of()));

        assertEquals(2, events.size(), "a single invoke emits exactly a request and a response event");
        assertInstanceOf(ModelRequestEvent.class, events.get(0));
        assertInstanceOf(ModelResponseEvent.class, events.get(1));
        assertEquals("ToolThenAnswerModel", ((ModelResponseEvent) events.get(1)).modelName());
    }

    @Test
    void rawChatDoesNotEmit() {
        AbstractEventBus bus = new InMemoryEventBus();
        List<Event> events = new ArrayList<>();
        bus.subscribe(Event.class, events::add);

        // chat() is the raw provider call; only invoke() publishes events.
        ToolThenAnswerModel model = new ToolThenAnswerModel();
        model.setEventBus(bus);
        model.chat(new ModelRequest(List.of(Message.user("hi")), List.of()));

        assertTrue(events.isEmpty(), "a raw chat() call emits nothing");
    }
}
