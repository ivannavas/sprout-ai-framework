package io.github.ivannavas.sprout.executor;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.event.Event;
import io.github.ivannavas.sprout.event.ModelRequestEvent;
import io.github.ivannavas.sprout.event.ModelResponseEvent;
import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.StreamListener;

import java.util.function.Supplier;

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
     * Sends a request to the named model, overriding whatever model this executor is configured to use
     * externally (e.g. {@code anthropic.model.name}). Backends that can target a model per call
     * (Anthropic, OpenAI, ...) override this to honour {@code modelName}; the default ignores it and
     * delegates to {@link #chat(ModelRequest)}, which suits stubs that answer regardless of the model.
     */
    public ModelResponse chat(String modelName, ModelRequest request) {
        return chat(request);
    }

    /**
     * Runs {@link #chat(ModelRequest)} as an observable execution: publishes a {@link ModelRequestEvent}
     * before the call and a {@link ModelResponseEvent} after it. The agent loop calls this; call it
     * yourself (rather than {@link #chat}) to make a standalone model execution emit the same events.
     */
    public final ModelResponse invoke(ModelRequest request) {
        String executorName = getClass().getSimpleName();
        publish(new ModelRequestEvent(executorName, request));
        ModelResponse response = chat(request);
        publish(new ModelResponseEvent(executorName, response));
        return response;
    }

    /**
     * Same observable execution as {@link #invoke(ModelRequest)}, but calls {@link #chat(String, ModelRequest)}
     * so the request targets {@code modelName} rather than the externally configured model. Events still
     * carry the executor's simple class name.
     */
    public final ModelResponse invoke(String modelName, ModelRequest request) {
        String executorName = getClass().getSimpleName();
        publish(new ModelRequestEvent(executorName, request));
        ModelResponse response = chat(modelName, request);
        publish(new ModelResponseEvent(executorName, response));
        return response;
    }

    /**
     * Streams a response to {@code listener}. The default adapts the blocking {@link #invoke} result
     * into listener callbacks (so it emits the same events); override to stream tokens from a backend
     * that supports it.
     */
    public void chatStream(ModelRequest request, StreamListener listener) {
        stream(listener, () -> invoke(request));
    }

    /**
     * Same as {@link #chatStream(ModelRequest, StreamListener)}, but streams from {@code modelName}
     * rather than the externally configured model (it runs through {@link #invoke(String, ModelRequest)}).
     */
    public void chatStream(String modelName, ModelRequest request, StreamListener listener) {
        stream(listener, () -> invoke(modelName, request));
    }

    /** Adapts a blocking model {@code call} into {@code listener} callbacks, reporting any failure via {@code onError}. */
    private void stream(StreamListener listener, Supplier<ModelResponse> call) {
        try {
            ModelResponse response = call.get();
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
