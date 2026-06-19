package io.github.ivannavas.sprout.model;

/** Callback for streaming chat. All methods are optional. */
public interface StreamListener {

    default void onToken(String token) {
    }

    default void onToolCall(ToolCall toolCall) {
    }

    default void onComplete(ModelResponse response) {
    }

    default void onError(Throwable error) {
    }
}
