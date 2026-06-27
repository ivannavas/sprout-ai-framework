package io.github.ivannavas.sprout.monitoring;

import java.time.Instant;

/**
 * A finished agent run recorded into the usage store. {@code success} is false when the run aborted with
 * an exception, in which case {@code iterations} and the token counts are unknown and reported as zero
 * (a failed run carries no {@code AgentResult}).
 */
public record AgentRun(String agentName, boolean success, long iterations, long inputTokens, long outputTokens, Instant at) {
}
