package io.github.ivannavas.sprout.monitoring;

import java.time.Instant;

/**
 * A single model execution recorded into the usage store: which model ran, the tokens it consumed and
 * the cost derived from the configured {@link io.github.ivannavas.sprout.monitoring.pricing.PricingTable
 * pricing}. Both agent-driven and standalone model calls produce one of these.
 */
public record ModelCall(String modelName, long inputTokens, long outputTokens, double cost, Instant at) {
}
