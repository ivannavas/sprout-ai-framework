package io.github.ivannavas.sprout.monitoring;

/** Aggregated usage for one model: how many times it was called, the tokens it consumed and the cost. */
public record ModelUsage(String modelName, long calls, long inputTokens, long outputTokens, double cost) {

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    /** Combines two aggregates for the same model (used to fold a new {@link ModelCall} into the total). */
    public ModelUsage plus(ModelUsage other) {
        return new ModelUsage(modelName,
                calls + other.calls,
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cost + other.cost);
    }
}
