package io.github.ivannavas.sprout.model;

/**
 * A {@link Document} returned by a similarity search, paired with the {@code score} the
 * {@link io.github.ivannavas.sprout.abstrct.AbstractVectorStore} assigned it. Higher scores mean a closer
 * match; the exact scale (e.g. cosine similarity in {@code [-1, 1]}) depends on the store implementation.
 */
public record SearchResult(Document document, double score) {
}
