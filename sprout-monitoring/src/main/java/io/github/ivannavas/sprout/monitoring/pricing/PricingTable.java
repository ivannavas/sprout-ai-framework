package io.github.ivannavas.sprout.monitoring.pricing;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Maps a model name to its {@link Rate} and turns token counts into a cost. Model names are the executor
 * simple class names carried by the model events (e.g. {@code AnthropicModelExecutor}); a model with no
 * configured rate costs nothing, so tokens are still tracked while cost stays zero until you price it.
 *
 * <p>Rates are looked up lazily through a property function — the keys
 * {@code sprout.monitoring.pricing.<modelName>.input} and {@code .output}, each a price per one million
 * tokens — so only the pricing keys of the models actually seen are ever resolved. Programmatic overrides
 * registered with {@link #put(String, Rate)} take precedence.
 */
public final class PricingTable {

    /** A model's price per one million input and output tokens. */
    public record Rate(double inputPer1M, double outputPer1M) {
        public static final Rate FREE = new Rate(0, 0);
    }

    /** Configuration prefix for per-model rates: {@code sprout.monitoring.pricing.<model>.<input|output>}. */
    public static final String PREFIX = "sprout.monitoring.pricing.";

    private final Function<String, String> properties;
    private final Map<String, Rate> overrides = new ConcurrentHashMap<>();

    /** A table with no configured rates (every model is free until {@link #put(String, Rate) priced}). */
    public PricingTable() {
        this(key -> null);
    }

    /** A table that resolves rates from {@code properties} (a key &rarr; value lookup, e.g. the container). */
    public PricingTable(Function<String, String> properties) {
        this.properties = properties;
    }

    /** A table backed by a fixed property map. */
    public static PricingTable fromProperties(Map<String, String> properties) {
        return new PricingTable(properties::get);
    }

    /** Sets (or replaces) the rate for a model, taking precedence over any configured property. */
    public void put(String modelName, Rate rate) {
        overrides.put(modelName, rate);
    }

    /** The cost of a call against {@code modelName}; zero when the model has no configured rate. */
    public double costOf(String modelName, long inputTokens, long outputTokens) {
        Rate rate = rateFor(modelName);
        return inputTokens / 1_000_000.0 * rate.inputPer1M()
                + outputTokens / 1_000_000.0 * rate.outputPer1M();
    }

    private Rate rateFor(String modelName) {
        Rate override = overrides.get(modelName);
        if (override != null) {
            return override;
        }
        double input = parse(properties.apply(PREFIX + modelName + ".input"));
        double output = parse(properties.apply(PREFIX + modelName + ".output"));
        return input == 0 && output == 0 ? Rate.FREE : new Rate(input, output);
    }

    private static double parse(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
