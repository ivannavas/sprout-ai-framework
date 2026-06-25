package io.github.ivannavas.sprout.model;

import java.util.Map;

/**
 * A unit of knowledge held in an {@link io.github.ivannavas.sprout.abstrct.AbstractVectorStore}: its
 * {@code text} is what gets retrieved and shown to the model, {@code id} identifies it, {@code metadata}
 * carries arbitrary attributes (source, title, tags…) and {@code embedding} is its vector representation.
 * The embedding is {@code null} until computed — use {@link #withEmbedding(float[])} to attach one, as an
 * {@link io.github.ivannavas.sprout.embedding.EmbeddingModel} does during indexing.
 */
public record Document(String id, String text, Map<String, String> metadata, float[] embedding) {

    public static Document of(String id, String text) {
        return new Document(id, text, Map.of(), null);
    }

    public static Document of(String id, String text, Map<String, String> metadata) {
        return new Document(id, text, metadata, null);
    }

    /** Returns a copy of this document carrying {@code embedding}, leaving the original untouched. */
    public Document withEmbedding(float[] embedding) {
        return new Document(id, text, metadata, embedding);
    }
}
