package io.github.ivannavas.sprout.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider-agnostic embedding model: turns text into the dense vector an
 * {@link io.github.ivannavas.sprout.abstrct.AbstractVectorStore} indexes and searches. Implement
 * {@link #embed(String)} to call a concrete backend (Anthropic, OpenAI, a local model or a test stub) and
 * annotate the subclass with {@link io.github.ivannavas.sprout.annotation.Embedding &#64;Embedding} to
 * register it as a bean. The mirror of {@link io.github.ivannavas.sprout.executor.ModelExecutor} for the
 * retrieval side of RAG; all vectors a single model produces share one dimension.
 */
public abstract class EmbeddingModel {

    /** Embeds a single piece of text into a vector. */
    public abstract float[] embed(String text);

    /**
     * Embeds several texts, preserving order. The default embeds them one by one; override to exploit a
     * backend's batch endpoint, which is typically cheaper and faster for indexing.
     */
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }
}
