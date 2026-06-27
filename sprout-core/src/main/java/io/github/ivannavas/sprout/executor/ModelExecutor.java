package io.github.ivannavas.sprout.executor;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.event.Event;
import io.github.ivannavas.sprout.event.ModelRequestEvent;
import io.github.ivannavas.sprout.event.ModelResponseEvent;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.StreamListener;

/**
 * Provider-agnostic chat model. Implement {@link #chat(ModelRequest)} to call a concrete backend
 * (Anthropic, OpenAI, a local model or a test stub) and annotate the subclass with
 * {@link io.github.ivannavas.sprout.annotation.Model @Model} to register it as a bean.
 *
 * <p>{@link #chat(ModelRequest)} is the raw call. To have an execution publish a {@link ModelRequestEvent}
 * and a {@link ModelResponseEvent} on the application's event bus — so model usage is observable even with
 * no agent in the picture — call {@link #invoke(ModelRequest)} instead; that is what the agent loop uses.
 */
public abstract class ModelExecutor {

    private AbstractEventBus eventBus;

    /** Sends a request to the model and returns its full response. */
    public abstract ModelResponse chat(ModelRequest request);

    /**
     * Runs {@link #chat(ModelRequest)} as an observable execution: publishes a {@link ModelRequestEvent}
     * before the call and a {@link ModelResponseEvent} after it. The agent loop calls this; call it
     * yourself (rather than {@link #chat}) to make a standalone model execution emit the same events.
     */
    public final ModelResponse invoke(ModelRequest request) {
        String modelName = getClass().getSimpleName();
        publish(new ModelRequestEvent(modelName, request));
        ModelResponse response = chat(request);
        publish(new ModelResponseEvent(modelName, response));
        return response;
    }

    /**
     * Streams a response to {@code listener}. The default adapts the blocking {@link #invoke} result
     * into listener callbacks (so it emits the same events); override to stream tokens from a backend
     * that supports it.
     */
    public void chatStream(ModelRequest request, StreamListener listener) {
        try {
            ModelResponse response = invoke(request);
            if (response.message().content() != null) {
                listener.onToken(response.message().content());
            }
            response.message().toolCalls().forEach(listener::onToolCall);
            listener.onComplete(response);
        } catch (Exception e) {
            listener.onError(e);
        }
    }

    /**
     * Supplies the {@link AbstractEventBus} {@link #invoke} publishes to. Wired by the container during
     * startup; when unset (e.g. a model constructed directly in a test) {@code invoke} still runs and
     * simply emits nothing.
     */
    public void setEventBus(AbstractEventBus eventBus) {
        this.eventBus = eventBus;
    }

    /** Publishes {@code event} to the bus, or does nothing when no bus is wired. */
    protected final void publish(Event event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }
}
