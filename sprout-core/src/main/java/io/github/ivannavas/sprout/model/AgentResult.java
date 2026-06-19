package io.github.ivannavas.sprout.model;

/** Outcome of an agent run: the final {@code response}, how many iterations it took and total usage. */
public record AgentResult(
        String conversationId,
        String response,
        int iterations,
        TokenUsage totalUsage
) {
}
