package io.github.ivannavas.sprout.model;

/** Token counts for a model call. Accumulated across an agent run via {@link #plus(TokenUsage)}. */
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
