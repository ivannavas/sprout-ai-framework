package io.github.ivannavas.sprout.model;

/** A model's reply: the assistant {@link Message}, token {@link TokenUsage} and why it stopped. */
public record ModelResponse(Message message, TokenUsage usage, FinishReason finishReason) {
}
