package io.github.ivannavas.sprout.executor;

import io.github.ivannavas.sprout.model.ModelRequest;
import io.github.ivannavas.sprout.model.ModelResponse;
import io.github.ivannavas.sprout.model.StreamListener;

/**
 * Provider-agnostic chat model. Implement {@link #chat(ModelRequest)} to call a concrete backend
 * (Anthropic, OpenAI, a local model or a test stub) and annotate the subclass with
 * {@link io.github.ivannavas.sprout.annotation.Model @Model} to register it as a bean.
 */
public abstract class ModelExecutor {

    /** Sends a request to the model and returns its full response. */
    public abstract ModelResponse chat(ModelRequest request);

    /**
     * Streams a response to {@code listener}. The default adapts the blocking {@link #chat} result
     * into listener callbacks; override to stream tokens from a backend that supports it.
     */
    public void chatStream(ModelRequest request, StreamListener listener) {
        try {
            ModelResponse response = chat(request);
            if (response.message().content() != null) {
                listener.onToken(response.message().content());
            }
            response.message().toolCalls().forEach(listener::onToolCall);
            listener.onComplete(response);
        } catch (Exception e) {
            listener.onError(e);
        }
    }
}
