package io.github.ivannavas.sprout.impl;

import io.github.ivannavas.sprout.annotation.Embedding;
import io.github.ivannavas.sprout.embedding.EmbeddingModel;

import java.util.Locale;

/**
 * Default {@link EmbeddingModel}: a dependency-free embedding built with the hashing trick — each word is
 * hashed into one of a fixed number of dimensions and the resulting vector is L2-normalized. It is
 * deterministic and needs no API key, which makes it convenient for examples and tests, but it captures
 * only lexical overlap (shared words), not meaning. Swap in a provider-backed {@link EmbeddingModel} for
 * semantic retrieval in production.
 */
@Embedding
public class HashingEmbeddingModel extends EmbeddingModel {

    private final int dimensions;

    public HashingEmbeddingModel() {
        this(256);
    }

    public HashingEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.isEmpty()) {
                continue;
            }
            int hash = token.hashCode();
            int bucket = Math.floorMod(hash, dimensions);
            // The sign bit decides direction, so distinct words that land in the same bucket are less
            // likely to simply reinforce one another.
            vector[bucket] += (hash & 1) == 0 ? 1f : -1f;
        }
        normalize(vector);
        return vector;
    }

    private static void normalize(float[] vector) {
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        if (norm == 0) {
            return;
        }
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
    }
}
