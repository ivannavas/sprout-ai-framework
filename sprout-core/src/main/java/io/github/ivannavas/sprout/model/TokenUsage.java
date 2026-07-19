package io.github.ivannavas.sprout.model;

/**
 * Token counts for a model call. Accumulated across an agent run via {@link #plus(TokenUsage)}.
 *
 * <p>{@code cacheWriteTokens} and {@code cacheReadTokens} report prompt caching, where a provider
 * stores a prompt prefix and reuses it on later calls that share it. They are counted separately
 * from {@code inputTokens} because they are billed differently — a cache read is typically a small
 * fraction of the base input price, and a write a premium over it — so a run that looks expensive
 * on raw token count may not be, and vice versa.
 *
 * <p>Providers that do not report caching leave both at zero, which is also what the
 * {@link #TokenUsage(long, long)} constructor produces.
 */
public record TokenUsage(long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, 0, 0);

    /** Usage for a call with no prompt caching, or from a provider that does not report it. */
    public TokenUsage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                cacheWriteTokens + other.cacheWriteTokens,
                cacheReadTokens + other.cacheReadTokens);
    }

    /**
     * Every token the prompt was made of, however it was billed: the uncached remainder plus what was
     * written to and read from the cache. {@link #inputTokens} alone understates the prompt whenever
     * caching is in play, so this is the figure to compare against a context window.
     */
    public long totalInputTokens() {
        return inputTokens + cacheWriteTokens + cacheReadTokens;
    }

    /**
     * The share of the prompt served from cache, in {@code [0, 1]} — 0 when nothing was cached and
     * when there was no prompt at all. Repeated calls over a shared prefix that stay near zero mean
     * the cache is being missed: usually something varies inside the prefix on every call.
     */
    public double cacheHitRatio() {
        long total = totalInputTokens();
        return total == 0 ? 0 : (double) cacheReadTokens / total;
    }
}
