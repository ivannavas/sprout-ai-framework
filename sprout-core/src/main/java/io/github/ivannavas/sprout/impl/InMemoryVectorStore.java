package io.github.ivannavas.sprout.impl;

import io.github.ivannavas.sprout.abstrct.AbstractVectorStore;
import io.github.ivannavas.sprout.annotation.VectorStore;
import io.github.ivannavas.sprout.model.Document;
import io.github.ivannavas.sprout.model.SearchResult;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link AbstractVectorStore}: holds documents in memory and ranks them by cosine similarity with
 * a brute-force scan. Indexing the same {@link Document#id() id} again replaces the previous entry. Safe
 * for concurrent use, but contents are lost when the JVM stops and the linear scan does not scale to large
 * corpora — back retrieval with a vector database (e.g. pgvector) for durability or scale.
 */
@VectorStore
public class InMemoryVectorStore implements AbstractVectorStore {

    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    @Override
    public void add(List<Document> documents) {
        for (Document document : documents) {
            if (document.embedding() == null) {
                throw new IllegalArgumentException("Document " + document.id() + " has no embedding; embed it before indexing");
            }
            this.documents.put(document.id(), document);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        return documents.values().stream()
                .map(document -> new SearchResult(document, cosineSimilarity(queryEmbedding, document.embedding())))
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(Math.max(0, topK))
                .toList();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embedding dimensions differ: " + a.length + " vs " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
