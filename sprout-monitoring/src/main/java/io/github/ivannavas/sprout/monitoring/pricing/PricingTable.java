package io.github.ivannavas.sprout.monitoring.pricing;

import io.github.ivannavas.sprout.model.TokenUsage;

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

    /**
     * A model's price per one million input and output tokens, plus what cached input costs relative
     * to fresh input. Cache pricing is expressed as multipliers rather than absolute rates so that
     * pricing a model stays a two-key job: a cache read is a fraction of the input price and a write a
     * premium over it, and those ratios hold across models even as the base rates change.
     *
     * <p>The defaults are Anthropic's published ratios. Override them per model when a provider prices
     * caching differently.
     */
    public record Rate(double inputPer1M, double outputPer1M,
                       double cacheReadMultiplier, double cacheWriteMultiplier) {

        /** A cache read costs a tenth of fresh input. */
        public static final double DEFAULT_CACHE_READ_MULTIPLIER = 0.1;
        /** Writing an entry costs a quarter more than fresh input; it pays back on the second read. */
        public static final double DEFAULT_CACHE_WRITE_MULTIPLIER = 1.25;

        public static final Rate FREE = new Rate(0, 0);

        /** A rate priced only on input and output, using the default cache multipliers. */
        public Rate(double inputPer1M, double outputPer1M) {
            this(inputPer1M, outputPer1M, DEFAULT_CACHE_READ_MULTIPLIER, DEFAULT_CACHE_WRITE_MULTIPLIER);
        }
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

    /**
     * The cost of a call, billing cached input at its own rate. Prefer this over
     * {@link #costOf(String, long, long)} whenever prompt caching may be in play: that overload sees
     * only the uncached remainder and so reports a caching run as far cheaper than it was.
     */
    public double costOf(String modelName, TokenUsage usage) {
        Rate rate = rateFor(modelName);
        double inputPerToken = rate.inputPer1M() / 1_000_000.0;
        return usage.inputTokens() * inputPerToken
                + usage.cacheReadTokens() * inputPerToken * rate.cacheReadMultiplier()
                + usage.cacheWriteTokens() * inputPerToken * rate.cacheWriteMultiplier()
                + usage.outputTokens() / 1_000_000.0 * rate.outputPer1M();
    }

    private Rate rateFor(String modelName) {
        Rate override = overrides.get(modelName);
        if (override != null) {
            return override;
        }
        double input = parse(properties.apply(PREFIX + modelName + ".input"));
        double output = parse(properties.apply(PREFIX + modelName + ".output"));
        if (input == 0 && output == 0) {
            return Rate.FREE;
        }
        return new Rate(input, output,
                parse(properties.apply(PREFIX + modelName + ".cache-read-multiplier"),
                        Rate.DEFAULT_CACHE_READ_MULTIPLIER),
                parse(properties.apply(PREFIX + modelName + ".cache-write-multiplier"),
                        Rate.DEFAULT_CACHE_WRITE_MULTIPLIER));
    }

    /** Parses {@code value}, falling back to {@code fallback} when it is absent or unreadable. */
    private static double parse(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
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
